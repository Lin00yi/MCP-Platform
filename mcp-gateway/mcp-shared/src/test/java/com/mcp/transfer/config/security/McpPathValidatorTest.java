package com.mcp.transfer.config.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpPathValidatorTest {

    @Test
    void normalizesValidPath() {
        assertEquals("/mcp/v1/demo", McpPathValidator.validateAndNormalize("/mcp//v1/demo/"));
        assertEquals("/a/B_c-1.2~3", McpPathValidator.validateAndNormalize("/a/B_c-1.2~3"));
    }

    @Test
    void rejectsReservedOrDangerousPaths() {
        assertThrows(IllegalArgumentException.class, () -> McpPathValidator.validateAndNormalize("/mcp/admin/tools"));
        assertThrows(IllegalArgumentException.class, () -> McpPathValidator.validateAndNormalize("/mcp/log/export"));
        assertThrows(IllegalArgumentException.class, () -> McpPathValidator.validateAndNormalize("/error"));
        assertThrows(IllegalArgumentException.class, () -> McpPathValidator.validateAndNormalize("/**"));
        assertThrows(IllegalArgumentException.class, () -> McpPathValidator.validateAndNormalize("/a/../b"));
        assertThrows(IllegalArgumentException.class, () -> McpPathValidator.validateAndNormalize("/a b"));
        assertThrows(IllegalArgumentException.class, () -> McpPathValidator.validateAndNormalize("relative"));
        assertThrows(IllegalArgumentException.class, () -> McpPathValidator.validateAndNormalize("/"));
    }
}
