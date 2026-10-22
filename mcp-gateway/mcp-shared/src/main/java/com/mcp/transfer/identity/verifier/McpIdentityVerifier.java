package com.mcp.transfer.identity.verifier;

import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.crypto.RSADecrypter;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.util.StringUtils;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 下游 MCP Server 验签器（方案 A：JWE 外层 + JWS 内层）。
 *
 * <pre>
 * McpIdentityVerifier verifier = new McpIdentityVerifier(
 *     gatewayPublicKeyPem,        // 网关签名公钥
 *     downstreamPrivateKeyPem,    // 本服务的 JWE 解密私钥
 *     "mcp-gateway",              // 期望 issuer
 *     "pcb-server");              // 本服务 audience
 * VerifiedIdentity identity = verifier.verify(request.getHeader("Authorization"));
 * </pre>
 *
 * 下游业务只允许使用 {@code identity.customerId()}，禁止从请求参数再次读取客编。
 */
public class McpIdentityVerifier {

    private static final long CLOCK_SKEW_SECONDS = 30;

    private final RSAPublicKey gatewayPublicKey;
    private final RSAPrivateKey downstreamPrivateKey;
    private final String expectedIssuer;
    private final String expectedAudience;

    /** jti → 过期时间，防重放 */
    private final Map<String, Long> replayCache = new ConcurrentHashMap<>();

    public McpIdentityVerifier(String gatewayPublicKeyPem, String downstreamPrivateKeyPem,
                               String expectedIssuer, String expectedAudience) {
        this.gatewayPublicKey = (RSAPublicKey) parsePublicKey(gatewayPublicKeyPem);
        this.downstreamPrivateKey = (RSAPrivateKey) parsePrivateKey(downstreamPrivateKeyPem);
        this.expectedIssuer = expectedIssuer;
        this.expectedAudience = expectedAudience;
    }

    /**
     * 校验 Authorization 头（支持 {@code Bearer <JWE>} 或裸 JWE）。
     */
    public VerifiedIdentity verify(String authorizationOrToken) {
        String token = stripBearer(authorizationOrToken);
        if (!StringUtils.hasText(token)) {
            throw new IdentityVerificationException("缺少身份断言");
        }
        try {
            JWEObject jwe = JWEObject.parse(token);
            jwe.decrypt(new RSADecrypter(downstreamPrivateKey));
            SignedJWT signedJWT = jwe.getPayload().toSignedJWT();
            if (signedJWT == null) {
                throw new IdentityVerificationException("JWE 内层不是 JWS");
            }
            if (!signedJWT.verify(new RSASSAVerifier(gatewayPublicKey))) {
                throw new IdentityVerificationException("JWS 验签失败");
            }
            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
            validateClaims(claims);
            return toIdentity(claims);
        } catch (IdentityVerificationException e) {
            throw e;
        } catch (Exception e) {
            throw new IdentityVerificationException("身份断言解析失败", e);
        }
    }

    private void validateClaims(JWTClaimsSet claims) {
        long now = System.currentTimeMillis() / 1000;
        if (!expectedIssuer.equals(claims.getIssuer())) {
            throw new IdentityVerificationException("iss 不匹配");
        }
        if (claims.getAudience() == null || !claims.getAudience().contains(expectedAudience)) {
            throw new IdentityVerificationException("aud 不匹配");
        }
        if (claims.getExpirationTime() == null || claims.getExpirationTime().toInstant().getEpochSecond() + CLOCK_SKEW_SECONDS < now) {
            throw new IdentityVerificationException("断言已过期");
        }
        if (claims.getIssueTime() != null && claims.getIssueTime().toInstant().getEpochSecond() - CLOCK_SKEW_SECONDS > now) {
            throw new IdentityVerificationException("iat 非法");
        }
        String jti = claims.getJWTID();
        if (!StringUtils.hasText(jti)) {
            throw new IdentityVerificationException("缺少 jti");
        }
        long exp = claims.getExpirationTime().toInstant().getEpochSecond();
        Long previous = replayCache.putIfAbsent(jti, exp);
        if (previous != null) {
            throw new IdentityVerificationException("断言重放");
        }
        cleanupReplayCache(now);
    }

    private void cleanupReplayCache(long now) {
        if (replayCache.size() < 10_000) {
            return;
        }
        replayCache.entrySet().removeIf(e -> e.getValue() + CLOCK_SKEW_SECONDS < now);
    }

    private VerifiedIdentity toIdentity(JWTClaimsSet claims) throws Exception {
        VerifiedIdentity identity = new VerifiedIdentity();
        identity.setAppId(claims.getStringClaim("app"));
        identity.setUserId(claims.getStringClaim("uid"));
        identity.setTenantId(claims.getIntegerClaim("tid"));
        identity.setCustomerId(claims.getStringClaim("cust"));
        identity.setTraceId(claims.getStringClaim("trace"));
        identity.setJwtId(claims.getJWTID());
        identity.setExpiresAt(claims.getExpirationTime() == null ? null : claims.getExpirationTime().getTime() / 1000);
        Object scope = claims.getClaim("scope");
        if (scope instanceof java.util.List) {
            for (Object s : (java.util.List<?>) scope) {
                identity.getScopes().add(String.valueOf(s));
            }
        }
        if (!StringUtils.hasText(identity.getCustomerId())) {
            throw new IdentityVerificationException("断言缺少客编");
        }
        return identity;
    }

    private String stripBearer(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.regionMatches(true, 0, "Bearer ", 0, 7) ? v.substring(7).trim() : v;
    }

    private PublicKey parsePublicKey(String pemOrBase64) {
        try {
            if (pemOrBase64.contains("BEGIN")) {
                JWK jwk = RSAKey.parseFromPEMEncodedObjects(pemOrBase64);
                return ((RSAKey) jwk).toRSAPublicKey();
            }
            byte[] der = Base64.getDecoder().decode(pemOrBase64.replaceAll("\\s", ""));
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IdentityVerificationException("网关公钥解析失败", e);
        }
    }

    private PrivateKey parsePrivateKey(String pemOrBase64) {
        try {
            if (pemOrBase64.contains("BEGIN")) {
                JWK jwk = RSAKey.parseFromPEMEncodedObjects(pemOrBase64);
                return ((RSAKey) jwk).toRSAPrivateKey();
            }
            byte[] der = Base64.getDecoder().decode(pemOrBase64.replaceAll("\\s", ""));
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IdentityVerificationException("下游解密私钥解析失败", e);
        }
    }
}
