package com.mcp.transfer.identity.verifier;

import com.mcp.transfer.identity.IdentityProperties;
import com.mcp.transfer.identity.InternalIdentityTokenService;
import com.mcp.transfer.identity.ResolvedIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GatewayOriginVerifierTest {

    private InternalIdentityTokenService tokenService;

    @BeforeEach
    void setUp() {
        IdentityProperties properties = new IdentityProperties();
        properties.setEnabled(true);
        properties.setTokenFormat("JWS");
        properties.setIssuer("mcp-gateway");
        properties.setSigningKid("gw-test");
        properties.setTtlSeconds(60);
        tokenService = new InternalIdentityTokenService(properties);
        tokenService.init();
    }

    @Test
    void verifiesGatewayJwsWithoutCustomer() {
        ResolvedIdentity identity = new ResolvedIdentity();
        identity.setAppId("JLC-Work");
        identity.setTenantId(5);
        identity.setUserTokenHash("hash-1");

        String token = tokenService.issue(identity, "pcb-server", null);

        GatewayOriginVerifier verifier = new GatewayOriginVerifier(
                tokenService.getSigningPublicKeyPem(), "mcp-gateway", "pcb-server");
        VerifiedIdentity verified = verifier.verify("Bearer " + token);

        assertEquals("JLC-Work", verified.getAppId());
        assertEquals(5, verified.getTenantId());
        assertNull(verified.getUserId());
        assertNull(verified.getCustomerId());
    }

    @Test
    void rejectsWrongAudience() {
        ResolvedIdentity identity = new ResolvedIdentity();
        identity.setAppId("JLC-Work");
        identity.setTenantId(5);
        String token = tokenService.issue(identity, "other-server", null);

        GatewayOriginVerifier verifier = new GatewayOriginVerifier(
                tokenService.getSigningPublicKeyPem(), "mcp-gateway", "pcb-server");
        assertThrows(IdentityVerificationException.class, () -> verifier.verify(token));
    }

    @Test
    void rejectsReplay() {
        ResolvedIdentity identity = new ResolvedIdentity();
        identity.setAppId("JLC-Work");
        identity.setTenantId(5);
        String token = tokenService.issue(identity, "pcb-server", null);

        GatewayOriginVerifier verifier = new GatewayOriginVerifier(
                tokenService.getSigningPublicKeyPem(), "mcp-gateway", "pcb-server");
        verifier.verify(token);
        assertThrows(IdentityVerificationException.class, () -> verifier.verify(token));
    }
}
