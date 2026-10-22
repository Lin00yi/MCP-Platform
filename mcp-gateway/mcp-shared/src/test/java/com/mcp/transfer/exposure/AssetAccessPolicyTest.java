package com.mcp.transfer.exposure;

import com.mcp.transfer.cache.PromptListCache;
import com.mcp.transfer.cache.ResourceListCache;
import com.mcp.transfer.entity.McpPrompt;
import com.mcp.transfer.entity.McpResource;
import com.mcp.transfer.entity.McpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetAccessPolicyTest {

    private AssetAccessPolicy policy;
    private McpServer server;
    private List<McpPrompt> prompts;
    private List<McpResource> resources;

    @BeforeEach
    void setUp() {
        policy = new AssetAccessPolicy();
        ReflectionTestUtils.setField(policy, "promptListCache", new PromptListCache());
        ReflectionTestUtils.setField(policy, "resourceListCache", new ResourceListCache());
        server = new McpServer().setId(10).setPromptAccessMode("ALL").setResourceAccessMode("ALL");
        prompts = Arrays.asList(
                new McpPrompt().setId(1).setServerId(10).setName("p1"),
                new McpPrompt().setId(2).setServerId(10).setName("p2"));
        resources = Arrays.asList(
                new McpResource().setId(11).setServerId(10).setName("r1"),
                new McpResource().setId(12).setServerId(10).setName("r2"));
    }

    @Test
    void allModeReturnsEverything() {
        assertEquals(2, policy.filterPrompts(server, "app1", false, prompts).size());
        assertEquals(2, policy.filterResources(server, "app1", false, resources).size());
        assertTrue(policy.canUsePrompt(server, "app1", prompts.get(0)));
        assertTrue(policy.canUseResource(server, "app1", resources.get(0)));
    }

    @Test
    void whitelistModeNoLongerFiltersAssets() {
        server.setPromptAccessMode("WHITELIST").setResourceAccessMode("WHITELIST");
        assertEquals(2, policy.filterPrompts(server, "app1", false, prompts).size());
        assertEquals(2, policy.filterResources(server, "app1", false, resources).size());
        assertTrue(policy.canUsePrompt(server, "app1", prompts.get(0)));
        assertTrue(policy.canUseResource(server, "app1", resources.get(0)));
    }
}
