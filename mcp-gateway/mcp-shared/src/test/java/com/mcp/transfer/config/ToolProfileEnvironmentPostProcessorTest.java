package com.mcp.transfer.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ToolProfileEnvironmentPostProcessorTest {

    private final ToolProfileEnvironmentPostProcessor processor = new ToolProfileEnvironmentPostProcessor();

    @Test
    void compactProfileAppliesPreset() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("MCP_TOOLS_PROFILE", "compact");

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertEquals("100", environment.getProperty("mcp.tools.page-size"));
        assertEquals("60", environment.getProperty("mcp.tools.warn-threshold"));
        assertEquals("3000", environment.getProperty("mcp.tools.result-spill-threshold"));
    }

    @Test
    void explicitPropertyStillWins() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("MCP_TOOLS_PROFILE", "compact")
                .withProperty("mcp.tools.page-size", "120");

        processor.postProcessEnvironment(environment, new SpringApplication());

        // 已显式声明的键不会被预设覆盖，其余键仍应用预设
        assertEquals("120", environment.getProperty("mcp.tools.page-size"));
        assertEquals("60", environment.getProperty("mcp.tools.warn-threshold"));
    }

    @Test
    void defaultOrUnknownProfileDoesNotInject() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("MCP_TOOLS_PROFILE", "unknown");

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertNull(environment.getProperty("mcp.tools.page-size"));
    }
}
