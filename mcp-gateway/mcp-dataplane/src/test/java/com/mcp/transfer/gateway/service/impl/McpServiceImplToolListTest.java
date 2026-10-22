package com.mcp.transfer.gateway.service.impl;

import com.mcp.transfer.cache.ToolListCache;
import com.mcp.transfer.dto.mcp.result.tools.McpToolsListResult;
import com.mcp.transfer.entity.McpServer;
import com.mcp.transfer.entity.McpTool;
import com.mcp.transfer.exposure.MetaToolFactory;
import com.mcp.transfer.exposure.ToolAccessPolicy;
import com.mcp.transfer.exposure.ToolBudgetAlerter;
import com.mcp.transfer.exposure.ToolExposureModes;
import com.mcp.transfer.exposure.ToolExposureProperties;
import com.mcp.transfer.exposure.ToolSchemaTrimmer;
import com.mcp.transfer.mapper.McpServerMapper;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

/**
 * 验证 tools/list 的三条主链路：DIRECT 分页、META 元工具、WHITELIST 过滤。
 */
@ExtendWith(MockitoExtension.class)
class McpServiceImplToolListTest {

    private static final String PATH = "/mcp/v1/pcb";

    @Mock
    private McpServerMapper mcpServerMapper;

    @Mock
    private McpToolMapper mcpToolMapper;

    @Mock
    private ToolBudgetAlerter toolBudgetAlerter;

    private McpServiceImpl service;

    private ToolExposureProperties properties;

    private final McpServer server = new McpServer()
            .setId(1)
            .setName("pcb")
            .setPath(PATH)
            .setStatus(1);

    @BeforeEach
    void setUp() {
        properties = new ToolExposureProperties();
        properties.setPageSize(2);
        properties.setPaginationMinTools(0);

        ToolListCache cache = new ToolListCache();
        ToolAccessPolicy policy = new ToolAccessPolicy();
        ReflectionTestUtils.setField(policy, "mcpToolMapper", mcpToolMapper);
        ReflectionTestUtils.setField(policy, "toolListCache", cache);
        ReflectionTestUtils.setField(policy, "properties", properties);
        ReflectionTestUtils.setField(policy, "tokenEstimator", new com.mcp.transfer.exposure.TokenEstimator());

        ToolSchemaTrimmer trimmer = new ToolSchemaTrimmer();
        ReflectionTestUtils.setField(trimmer, "properties", properties);

        service = new McpServiceImpl();
        ReflectionTestUtils.setField(service, "mcpServerMapper", mcpServerMapper);
        ReflectionTestUtils.setField(service, "mcpToolMapper", mcpToolMapper);
        ReflectionTestUtils.setField(service, "toolAccessPolicy", policy);
        ReflectionTestUtils.setField(service, "toolExposureProperties", properties);
        ReflectionTestUtils.setField(service, "toolSchemaTrimmer", trimmer);
        ReflectionTestUtils.setField(service, "toolBudgetAlerter", toolBudgetAlerter);
        ReflectionTestUtils.setField(service, "tokenEstimator", new com.mcp.transfer.exposure.TokenEstimator());
        ReflectionTestUtils.setField(service, "toolPaginationTelemetry", new com.mcp.transfer.exposure.ToolPaginationTelemetry());
        doNothing().when(toolBudgetAlerter).check(any(), any());
    }

    @Test
    void directModePaginatesWithCursor() {
        server.setToolAccessMode(ToolExposureModes.ACCESS_ALL);
        when(mcpServerMapper.selectOneByPath(PATH)).thenReturn(server);
        when(mcpToolMapper.selectAllByServerId(1)).thenReturn(tools("t1", "t2", "t3"));

        McpToolsListResult first = invokeList(null);

        assertEquals(Arrays.asList("t1", "t2"), names(first));
        assertNotNull(first.getNextCursor());
        assertEquals(3, first.getMeta().get("visibleTools"));
        assertEquals(2, first.getMeta().get("returned"));

        McpToolsListResult second = invokeList(first.getNextCursor());

        assertEquals(Collections.singletonList("t3"), names(second));
        assertNull(second.getNextCursor());
    }

    @Test
    void metaModeReturnsHotToolsAndMetaTools() {
        server.setToolAccessMode(ToolExposureModes.ACCESS_ALL)
                .setToolExposureMode(ToolExposureModes.EXPOSURE_META)
                .setHotToolCodes("[\"t2\"]");
        when(mcpServerMapper.selectOneByPath(PATH)).thenReturn(server);
        when(mcpToolMapper.selectAllByServerId(1)).thenReturn(tools("t1", "t2"));

        McpToolsListResult result = invokeList(null);

        assertEquals(Arrays.asList("t2", MetaToolFactory.SEARCH_TOOLS,
                MetaToolFactory.GET_TOOL_SCHEMA, MetaToolFactory.CALL_TOOL,
                MetaToolFactory.READ_RESULT_CHUNK), names(result));
        assertNull(result.getNextCursor());
        assertEquals(1, result.getMeta().get("hotTools"));
        assertEquals("META", result.getMeta().get("exposureMode"));
    }

    @Test
    void unknownClientPaginationIsFlagged() {
        server.setToolAccessMode(ToolExposureModes.ACCESS_ALL);
        properties.setPaginationMinTools(1);
        properties.setPaginationClients("codex,claude");
        properties.setPaginationUnknownPolicy("PAGINATE");
        when(mcpServerMapper.selectOneByPath(PATH)).thenReturn(server);
        when(mcpToolMapper.selectAllByServerId(1)).thenReturn(tools("t1", "t2", "t3"));

        McpToolsListResult unknown = invokeList(null, "some-unknown-client");
        assertNotNull(unknown.getNextCursor());
        assertEquals(true, unknown.getMeta().get("paginationRisk"));
        assertEquals("some-unknown-client", unknown.getMeta().get("clientName"));

        McpToolsListResult codex = invokeList(null, "codex-cli");
        assertNotNull(codex.getNextCursor());
        assertEquals(false, codex.getMeta().get("paginationRisk"));
    }

    @Test
    void whitelistModeNoLongerFiltersTools() {
        server.setToolAccessMode(ToolExposureModes.ACCESS_WHITELIST);
        when(mcpServerMapper.selectOneByPath(PATH)).thenReturn(server);
        when(mcpToolMapper.selectAllByServerId(1)).thenReturn(tools("t1", "t2"));

        McpToolsListResult result = invokeList(null);

        assertEquals(Arrays.asList("t1", "t2"), names(result));
        assertEquals(2, result.getMeta().get("visibleTools"));
        assertEquals("WHITELIST", result.getMeta().get("accessMode"));
    }

    private McpToolsListResult invokeList(String cursor) {
        return invokeList(cursor, "codex");
    }

    private McpToolsListResult invokeList(String cursor, String clientName) {
        return (McpToolsListResult) ReflectionTestUtils.invokeMethod(
                service, "buildToolListResult", PATH, "app1", cursor, "e2e-session", clientName);
    }

    private List<String> names(McpToolsListResult result) {
        List<String> names = new java.util.ArrayList<>();
        for (com.mcp.transfer.dto.mcp.result.tools.McpTool tool : result.getTools()) {
            names.add(tool.getName());
        }
        return names;
    }

    private List<McpTool> tools(String... codes) {
        List<McpTool> tools = new java.util.ArrayList<>();
        for (int i = 0; i < codes.length; i++) {
            tools.add(new McpTool()
                    .setId(i + 1)
                    .setServerId(1)
                    .setCode(codes[i])
                    .setName(codes[i])
                    .setStatus(1)
                    .setTags("pcb")
                    .setResJson("{\"name\":\"" + codes[i] + "\",\"description\":\"desc " + codes[i]
                            + "\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}"));
        }
        return tools;
    }
}
