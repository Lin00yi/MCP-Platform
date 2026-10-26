package com.mcp.transfer.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigSanityCheckerTest {

    @Test
    void productionRejectsStaticTokens() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.profiles.active", "prod")
                .withProperty("mcp.auth.user-token.static-tokens", "tok|u|n|c|read");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ConfigSanityChecker(env).run(null));
        assertTrue(error.getMessage().contains("MCP_AUTH_STATIC_TOKENS"));
    }

    @Test
    void requiredIdentityNeedsSigningKeyInProd() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.profiles.active", "prod")
                .withProperty("mcp.identity.enabled", "true")
                .withProperty("mcp.identity.mode", "REQUIRED")
                .withProperty("mcp.identity.signing-private-key", "");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ConfigSanityChecker(env).run(null));
        assertTrue(error.getMessage().contains("MCP_IDENTITY_SIGNING_KEY"));
    }

    @Test
    void redisEnabledNeedsUrl() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.profiles.active", "prod")
                .withProperty("mcp.redis.enabled", "true")
                .withProperty("mcp.redis.uri", "");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ConfigSanityChecker(env).run(null));
        assertTrue(error.getMessage().contains("MCP_REDIS_URL"));
    }

    @Test
    void redisEnabledWithExplicitUrlPasses() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.profiles.active", "prod")
                .withProperty("mcp.redis.enabled", "true")
                .withProperty("MCP_REDIS_URL", "redis://127.0.0.1:6379/0");

        assertDoesNotThrow(() -> new ConfigSanityChecker(env).run(null));
    }

    @Test
    void devRedisWithoutExplicitUrlOnlyWarns() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.profiles.active", "dev")
                .withProperty("mcp.redis.enabled", "true");

        assertDoesNotThrow(() -> new ConfigSanityChecker(env).run(null));
    }

    @Test
    void toolProfilePropertyIsValidated() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("mcp.tools.profile", "huge");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ConfigSanityChecker(env).run(null));
        assertTrue(error.getMessage().contains("MCP_TOOLS_PROFILE"));
    }

    @Test
    void devAllowsStaticTokensForE2E() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.profiles.active", "dev")
                .withProperty("mcp.auth.user-token.static-tokens", "tok|u|n|c|read");

        assertDoesNotThrow(() -> new ConfigSanityChecker(env).run(null));
    }

    @Test
    void unknownToolProfileIsRejected() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("MCP_TOOLS_PROFILE", "huge");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ConfigSanityChecker(env).run(null));
        assertTrue(error.getMessage().contains("MCP_TOOLS_PROFILE"));
    }
}
