package com.mcp.transfer.identity;

import com.mcp.transfer.shared.entity.McpServer;
import com.mcp.transfer.identity.verifier.GatewayOriginVerifier;
import com.mcp.transfer.identity.verifier.VerifiedIdentity;
import com.mcp.transfer.upstream.UpstreamDef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityAssertionResolverTest {

    private IdentityProperties properties;
    private InternalIdentityTokenService tokenService;
    private IdentityAssertionResolver resolver;

    @BeforeEach
    void setUp() {
        properties = new IdentityProperties();
        properties.setEnabled(true);
        properties.setMode("OPTIONAL");
        properties.setTokenFormat("JWS");
        properties.setIssuer("mcp-gateway");
        properties.setTtlSeconds(60);
        tokenService = new InternalIdentityTokenService(properties);
        tokenService.init();
        resolver = new IdentityAssertionResolver(properties, tokenService);
        McpIdentityContext.clear();
    }

    @AfterEach
    void tearDown() {
        McpIdentityContext.clear();
    }

    private McpServer server(String mode) {
        McpServer server = new McpServer();
        server.setId(7);
        server.setName("pcb");
        server.setAudience("pcb");
        server.setIdentityMode(mode);
        return server;
    }

    private ResolvedIdentity identity(String customerId) {
        ResolvedIdentity identity = new ResolvedIdentity();
        identity.setUserId("u-1");
        identity.setUserName("张三");
        identity.setCustomerId(customerId);
        identity.setScopes(Arrays.asList("tool:add"));
        return identity;
    }

    @Test
    void offModeNeverIssues() {
        McpIdentityContext.set(identity("C-1"));
        assertNull(resolver.resolve(server("OFF")));
    }

    @Test
    void optionalModeDegradesWithoutIdentity() {
        assertNull(resolver.resolve(server("OPTIONAL")));
    }

    @Test
    void requiredModeRejectsWithoutIdentity() {
        IdentityRequiredException error = assertThrows(IdentityRequiredException.class,
                () -> resolver.resolve(server("REQUIRED")));
        assertTrue(error.getMessage().contains("缺少用户身份"));
    }

    @Test
    void requiredModeRejectsWithoutCustomer() {
        McpIdentityContext.set(identity(null));
        IdentityRequiredException error = assertThrows(IdentityRequiredException.class,
                () -> resolver.resolve(server("REQUIRED")));
        assertTrue(error.getMessage().contains("客编"));
    }

    @Test
    void requiredModeIssuesVerifiableToken() {
        McpIdentityContext.set(identity("C-1"));
        String token = resolver.resolve(server("REQUIRED"));
        assertNotNull(token);

        GatewayOriginVerifier verifier = new GatewayOriginVerifier(
                tokenService.getSigningPublicKeyPem(), "mcp-gateway", "pcb");
        VerifiedIdentity verified = verifier.verify(token);
        assertEquals("u-1", verified.getUserId());
        assertEquals("C-1", verified.getCustomerId());
        assertTrue(verified.getScopes().contains("tool:add"));
    }

    @Test
    void globalRequiredIsOverriddenPerServer() {
        properties.setMode("REQUIRED");
        McpIdentityContext.set(identity("C-1"));
        assertNull(resolver.resolve(server("OFF")));
    }

    @Test
    void aggregateUpstreamOverridesAudience() {
        McpIdentityContext.set(identity("C-1"));
        UpstreamDef upstream = new UpstreamDef();
        upstream.setName("erp");
        upstream.setUrl("http://erp:8080/mcp");
        upstream.setAudience("erp-aud");
        String token = resolver.resolve(server("REQUIRED"), upstream.getIdentityMode(),
                upstream.getAudience(), upstream.getIdentityPublicKey());
        assertNotNull(token);

        VerifiedIdentity verified = new GatewayOriginVerifier(
                tokenService.getSigningPublicKeyPem(), "mcp-gateway", "erp-aud").verify(token);
        assertEquals("C-1", verified.getCustomerId());
    }

    @Test
    void healthProbeUsesServiceIdentityWithoutCustomer() {
        McpServer server = server("REQUIRED");
        String token = resolver.resolveForHealth(server, null);
        assertNotNull(token);

        VerifiedIdentity verified = new GatewayOriginVerifier(
                tokenService.getSigningPublicKeyPem(), "mcp-gateway", "pcb").verify(token);
        assertEquals(IdentityAssertionResolver.PROBE_SUBJECT, verified.getUserId());
        assertNull(verified.getCustomerId());
        assertTrue(verified.getScopes().contains(IdentityAssertionResolver.HEALTH_SCOPE));
    }

    @Test
    void jweModeWithoutDownstreamKeyDegradesOrFailsByMode() {
        properties.setTokenFormat("JWE");
        McpIdentityContext.set(identity("C-1"));

        assertNull(resolver.resolve(server("OPTIONAL")),
                "OPTIONAL 缺下游公钥时应降级为不下发");

        IdentityRequiredException error = assertThrows(IdentityRequiredException.class,
                () -> resolver.resolve(server("REQUIRED")));
        assertTrue(error.getMessage().contains("identity_public_key"));
    }
}
