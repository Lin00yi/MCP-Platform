package com.mcp.transfer.identity;

import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.crypto.RSADecrypter;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalIdentityTokenServiceTest {

    private IdentityProperties properties;
    private InternalIdentityTokenService service;
    private KeyPair downstreamKeyPair;

    @BeforeEach
    void setUp() throws Exception {
        properties = new IdentityProperties();
        properties.setEnabled(true);
        properties.setTokenFormat("JWE");
        properties.setIssuer("mcp-gateway");
        properties.setSigningKid("gw-test");
        properties.setTtlSeconds(60);
        // 不配置 signingPrivateKey：服务会生成临时密钥，便于单测
        service = new InternalIdentityTokenService(properties);
        service.init();

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        downstreamKeyPair = generator.generateKeyPair();
    }

    private ResolvedIdentity identity() {
        ResolvedIdentity identity = new ResolvedIdentity();
        identity.setAppId("JLC-Work");
        identity.setTenantId(5);
        identity.setUserId("u-123");
        identity.setUserName("张三");
        identity.setCustomerId("CUST-001");
        identity.setScopes(Arrays.asList("tool:add"));
        identity.setTraceId("trace-1");
        return identity;
    }

    @Test
    void issuesJweWithInnerJws() throws Exception {
        String publicPem = InternalIdentityTokenService.toPublicPem(downstreamKeyPair.getPublic());
        String token = service.issue(identity(), "pcb-server", publicPem);
        assertNotNull(token);
        assertEquals(5, token.split("\\.").length, "JWE compact serialization 应为五段");

        JWEObject jwe = JWEObject.parse(token);
        jwe.decrypt(new RSADecrypter((RSAPrivateKey) downstreamKeyPair.getPrivate()));
        SignedJWT signedJWT = jwe.getPayload().toSignedJWT();
        assertNotNull(signedJWT);

        JWK gatewayJwk = RSAKey.parseFromPEMEncodedObjects(service.getSigningPublicKeyPem());
        RSAPublicKey gatewayPublicKey = ((RSAKey) gatewayJwk).toRSAPublicKey();
        assertTrue(signedJWT.verify(new RSASSAVerifier(gatewayPublicKey)), "内层 JWS 验签应通过");

        JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
        assertEquals("mcp-gateway", claims.getIssuer());
        assertEquals("pcb-server", claims.getAudience().get(0));
        assertEquals("JLC-Work", claims.getStringClaim("app"));
        assertEquals("u-123", claims.getStringClaim("uid"));
        assertEquals(5, claims.getIntegerClaim("tid"));
        assertEquals("CUST-001", claims.getStringClaim("cust"));
        assertEquals("trace-1", claims.getStringClaim("trace"));
        assertNotNull(claims.getJWTID());
        assertNotNull(claims.getExpirationTime());
    }

    @Test
    void supportsJwsFallback() throws Exception {
        properties.setTokenFormat("JWS");
        InternalIdentityTokenService jwsService = new InternalIdentityTokenService(properties);
        jwsService.init();

        String token = jwsService.issue(identity(), "pcb-server", null);
        SignedJWT signedJWT = SignedJWT.parse(token);
        JWK gatewayJwk = RSAKey.parseFromPEMEncodedObjects(jwsService.getSigningPublicKeyPem());
        RSAPublicKey gatewayPublicKey = ((RSAKey) gatewayJwk).toRSAPublicKey();
        assertTrue(signedJWT.verify(new RSASSAVerifier(gatewayPublicKey)));
        assertEquals("CUST-001", signedJWT.getJWTClaimsSet().getStringClaim("cust"));
    }
}
