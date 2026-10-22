package com.mcp.transfer.identity.verifier;

import com.mcp.transfer.identity.IdentityProperties;
import com.mcp.transfer.identity.InternalIdentityTokenService;
import com.mcp.transfer.identity.ResolvedIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpIdentityVerifierTest {

    private InternalIdentityTokenService tokenService;
    private KeyPair downstreamKeyPair;
    private String downstreamPrivatePem;

    @BeforeEach
    void setUp() throws Exception {
        IdentityProperties properties = new IdentityProperties();
        properties.setEnabled(true);
        properties.setTokenFormat("JWE");
        properties.setIssuer("mcp-gateway");
        properties.setSigningKid("gw-test");
        properties.setTtlSeconds(60);
        tokenService = new InternalIdentityTokenService(properties);
        tokenService.init();

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        downstreamKeyPair = generator.generateKeyPair();
        downstreamPrivatePem = toPrivatePem(downstreamKeyPair.getPrivate());
    }

    private ResolvedIdentity identity() {
        ResolvedIdentity identity = new ResolvedIdentity();
        identity.setAppId("JLC-Work");
        identity.setTenantId(5);
        identity.setUserId("u-123");
        identity.setCustomerId("CUST-001");
        identity.setScopes(Arrays.asList("tool:add"));
        identity.setTraceId("trace-1");
        return identity;
    }

    @Test
    void verifiesJweAndExtractsIdentity() {
        String publicPem = InternalIdentityTokenService.toPublicPem(downstreamKeyPair.getPublic());
        String token = tokenService.issue(identity(), "pcb-server", publicPem);

        McpIdentityVerifier verifier = new McpIdentityVerifier(
                tokenService.getSigningPublicKeyPem(), downstreamPrivatePem, "mcp-gateway", "pcb-server");
        VerifiedIdentity verified = verifier.verify("Bearer " + token);

        assertEquals("JLC-Work", verified.getAppId());
        assertEquals("u-123", verified.getUserId());
        assertEquals(5, verified.getTenantId());
        assertEquals("CUST-001", verified.getCustomerId());
        assertEquals("trace-1", verified.getTraceId());
    }

    @Test
    void rejectsWrongAudience() {
        String publicPem = InternalIdentityTokenService.toPublicPem(downstreamKeyPair.getPublic());
        String token = tokenService.issue(identity(), "other-server", publicPem);

        McpIdentityVerifier verifier = new McpIdentityVerifier(
                tokenService.getSigningPublicKeyPem(), downstreamPrivatePem, "mcp-gateway", "pcb-server");
        assertThrows(IdentityVerificationException.class, () -> verifier.verify(token));
    }

    @Test
    void rejectsReplay() {
        String publicPem = InternalIdentityTokenService.toPublicPem(downstreamKeyPair.getPublic());
        String token = tokenService.issue(identity(), "pcb-server", publicPem);

        McpIdentityVerifier verifier = new McpIdentityVerifier(
                tokenService.getSigningPublicKeyPem(), downstreamPrivatePem, "mcp-gateway", "pcb-server");
        verifier.verify(token);
        assertThrows(IdentityVerificationException.class, () -> verifier.verify(token));
    }

    private static String toPrivatePem(PrivateKey key) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(key.getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----";
    }
}
