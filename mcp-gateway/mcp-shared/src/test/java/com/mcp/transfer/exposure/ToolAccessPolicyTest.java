package com.mcp.transfer.exposure;

import com.mcp.transfer.cache.ToolListCache;
import com.mcp.transfer.entity.McpServer;
import com.mcp.transfer.entity.McpTool;
import com.mcp.transfer.mapper.McpToolMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolAccessPolicyTest {

    @Mock
    private McpToolMapper mcpToolMapper;

    private ToolAccessPolicy policy;

    private final McpServer server = new McpServer().setId(10).setName("pcb");

    private final List<McpTool> tools = Arrays.asList(
            new McpTool().setId(1).setServerId(10).setCode("order_query").setTags("order,query"),
            new McpTool().setId(2).setServerId(10).setCode("order_create").setTags("order,create"));

    @BeforeEach
    void setUp() {
        policy = new ToolAccessPolicy();
        ReflectionTestUtils.setField(policy, "mcpToolMapper", mcpToolMapper);
        ReflectionTestUtils.setField(policy, "toolListCache", new ToolListCache());
        ReflectionTestUtils.setField(policy, "properties", new ToolExposureProperties());
        ReflectionTestUtils.setField(policy, "tokenEstimator", new TokenEstimator());
    }

    @Test
    void allEnabledToolsVisibleWithoutAppGrant() {
        server.setToolAccessMode(ToolExposureModes.ACCESS_WHITELIST);
        when(mcpToolMapper.selectAllByServerId(10)).thenReturn(tools);

        List<McpTool> visible = policy.visibleTools(server, "app1", false);

        assertEquals(2, visible.size());
        assertTrue(policy.canUse(server, "app1", tools.get(0)));
        assertTrue(policy.canUse(server, "app1", tools.get(1)));
    }

    @Test
    void hotToolsKeepConfiguredOrderAndSkipInvisibleOnes() {
        server.setHotToolCodes("[\"order_create\",\"order_query\",\"missing\"]");
        List<McpTool> visible = Collections.singletonList(tools.get(1));

        List<McpTool> hot = policy.hotTools(server, visible);

        assertEquals(1, hot.size());
        assertEquals("order_create", hot.get(0).getCode());
    }

    @Test
    void budgetFlagsOverWarnAndOverMax() {
        server.setToolAccessMode(ToolExposureModes.ACCESS_ALL)
                .setToolWarnThreshold(1)
                .setToolMaxThreshold(1);
        when(mcpToolMapper.selectAllByServerId(10)).thenReturn(tools);

        ToolBudget budget = policy.budget(server, "app1", false);

        assertEquals(2, budget.getVisibleTools());
        assertTrue(budget.isOverWarn());
        assertTrue(budget.isOverMax());
        assertTrue(budget.getEstimatedTokens() > 0);
    }
}
