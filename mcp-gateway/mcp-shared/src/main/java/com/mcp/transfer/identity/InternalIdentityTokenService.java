package com.mcp.transfer.identity;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSAEncrypter;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * 内部身份断言签发：内层 JWS（RS256）+ 外层 JWE（RSA-OAEP-256/A256GCM）。
 *
 * <p>安全约束：
 * <ul>
 *   <li>默认 JWE；客编敏感时不建议降级 JWS；</li>
 *   <li>TTL 默认 60s，`aud` 按下游限定，`jti` 防重放；</li>
 *   <li>签名私钥只存在网关；下游只持有网关公钥 + 自己的解密私钥。</li>
 * </ul>
 */
@Slf4j
@Component
public class InternalIdentityTokenService {

    private final IdentityProperties properties;

    private RSASSASigner signer;
    private RSAPublicKey signingPublicKey;

    public InternalIdentityTokenService(IdentityProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            log.info("[IDENTITY] 身份下传未启用，内部断言服务不初始化密钥");
            return;
        }
        String pem = properties.getSigningPrivateKey();
        if (!StringUtils.hasText(pem)) {
            KeyPair keyPair = generateKeyPair();
            this.signer = new RSASSASigner(keyPair.getPrivate());
            this.signingPublicKey = (RSAPublicKey) keyPair.getPublic();
            log.warn("======================================================================");
            log.warn("[IDENTITY] 未配置 mcp.identity.signing-private-key，已生成临时签名密钥");
            log.warn("[IDENTITY] 下游验签公钥（仅本次启动有效）：\n{}", toPublicPem(signingPublicKey));
            log.warn("[IDENTITY] 生产环境必须配置固定私钥，否则重启后下游无法验签");
            log.warn("======================================================================");
            return;
        }
        try {
            PrivateKey privateKey = parsePrivateKey(pem);
            this.signer = new RSASSASigner((RSAPrivateKey) privateKey);
            this.signingPublicKey = derivePublicKey((RSAPrivateKey) privateKey);
            log.info("[IDENTITY] 内部断言签名密钥初始化完成，kid={}", properties.getSigningKid());
        } catch (Exception e) {
            throw new IllegalStateException("mcp.identity.signing-private-key 解析失败", e);
        }
    }

    /**
     * 签发内部身份断言。
     *
     * @param identity               运行时身份
     * @param audience               下游 audience
     * @param downstreamPublicKeyPem 下游 JWE 公钥；JWE 模式必填
     */
    public String issue(ResolvedIdentity identity, String audience, String downstreamPublicKeyPem) {
        if (!properties.isEnabled()) {
            return null;
        }
        if (signer == null) {
            throw new IllegalStateException("内部断言签名密钥未初始化");
        }
        if (!StringUtils.hasText(audience)) {
            throw new IllegalArgumentException("内部断言 audience 不能为空");
        }
        try {
            Instant now = Instant.now();
            String subject = StringUtils.hasText(identity.getUserId()) ? identity.getUserId() : "unknown";
            JWTClaimsSet.Builder claimsBuilder = new JWTClaimsSet.Builder()
                    .issuer(properties.getIssuer())
                    .audience(audience)
                    .subject(subject)
                    .claim("scope", identity.getScopes())
                    .claim("trace", identity.getTraceId())
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(Math.max(10, properties.getTtlSeconds()))))
                    .jwtID(UUID.randomUUID().toString());
            // app / tid 仅在有值时写入；P1 起身份上下文不再有 tenant，默认不出现
            if (StringUtils.hasText(identity.getAppId())) {
                claimsBuilder.claim("app", identity.getAppId());
            }
            if (identity.getTenantId() != null) {
                claimsBuilder.claim("tid", identity.getTenantId());
            }
            if (StringUtils.hasText(identity.getUserId())) {
                claimsBuilder.claim("uid", identity.getUserId());
            }
            if (StringUtils.hasText(identity.getCustomerId())) {
                claimsBuilder.claim("cust", identity.getCustomerId());
            }
            JWTClaimsSet claims = claimsBuilder.build();

            JWSHeader jwsHeader = new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(properties.getSigningKid())
                    .type(JOSEObjectType.JWT)
                    .build();
            SignedJWT signedJWT = new SignedJWT(jwsHeader, claims);
            signedJWT.sign(signer);

            if (!"JWE".equalsIgnoreCase(properties.getTokenFormat())) {
                return signedJWT.serialize();
            }

            if (!StringUtils.hasText(downstreamPublicKeyPem)) {
                throw new IllegalStateException("JWE 模式必须配置下游公钥 mcp_server.identity_public_key");
            }
            PublicKey downstreamPublicKey = parsePublicKey(downstreamPublicKeyPem);
            JWEHeader jweHeader = new JWEHeader.Builder(JWEAlgorithm.RSA_OAEP_256, EncryptionMethod.A256GCM)
                    .contentType("JWT")
                    .keyID(audience)
                    .build();
            JWEObject jweObject = new JWEObject(jweHeader, new Payload(signedJWT.serialize()));
            jweObject.encrypt(new RSAEncrypter((RSAPublicKey) downstreamPublicKey));
            return jweObject.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("签发内部身份断言失败: " + e.getMessage(), e);
        }
    }

    public String getSigningPublicKeyPem() {
        return signingPublicKey == null ? null : toPublicPem(signingPublicKey);
    }

    // ============ 密钥解析 ============

    private PrivateKey parsePrivateKey(String pemOrBase64) throws Exception {
        if (pemOrBase64.contains("BEGIN")) {
            JWK jwk = RSAKey.parseFromPEMEncodedObjects(pemOrBase64);
            if (!(jwk instanceof RSAKey)) {
                throw new IllegalArgumentException("私钥不是 RSA 密钥");
            }
            return ((RSAKey) jwk).toRSAPrivateKey();
        }
        byte[] der = Base64.getDecoder().decode(pemOrBase64.replaceAll("\\s", ""));
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private PublicKey parsePublicKey(String pemOrBase64) throws Exception {
        if (pemOrBase64.contains("BEGIN")) {
            JWK jwk = RSAKey.parseFromPEMEncodedObjects(pemOrBase64);
            if (!(jwk instanceof RSAKey)) {
                throw new IllegalArgumentException("公钥不是 RSA 密钥");
            }
            return ((RSAKey) jwk).toRSAPublicKey();
        }
        byte[] der = Base64.getDecoder().decode(pemOrBase64.replaceAll("\\s", ""));
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    private RSAPublicKey derivePublicKey(RSAPrivateKey privateKey) throws Exception {
        java.security.interfaces.RSAPrivateCrtKey crt = (java.security.interfaces.RSAPrivateCrtKey) privateKey;
        java.security.spec.RSAPublicKeySpec spec = new java.security.spec.RSAPublicKeySpec(
                crt.getModulus(), crt.getPublicExponent());
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
    }

    private KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("生成 RSA 密钥失败", e);
        }
    }

    public static String toPublicPem(PublicKey publicKey) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(publicKey.getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----";
    }
}
