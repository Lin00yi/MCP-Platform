package com.mcp.transfer.gateway.aggregate;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mcp.transfer.config.session.McpSessionCache;
import com.mcp.transfer.dto.mcp.req.McpRequest;
import com.mcp.transfer.dto.mcp.req.Params;
import com.mcp.transfer.dto.mcp.res.McpResponse;
import com.mcp.transfer.dto.mcp.result.data.McpResData;
import com.mcp.transfer.dto.mcp.result.tools.McpToolsListResult;
import com.mcp.transfer.entity.McpServer;
import com.mcp.transfer.entity.McpTool;
import com.mcp.transfer.exposure.TokenEstimator;
import com.mcp.transfer.exposure.ToolAccessPolicy;
import com.mcp.transfer.exposure.ToolBudgetAlerter;
import com.mcp.transfer.exposure.ToolExposureProperties;
import com.mcp.transfer.exposure.ToolResultService;
import com.mcp.transfer.exposure.ToolSearchService;
import com.mcp.transfer.gateway.McpUpstreamClient;
import com.mcp.transfer.gateway.service.McpService;
import com.mcp.transfer.mapper.McpPromptMapper;
import com.mcp.transfer.mapper.McpResourceMapper;
import com.mcp.transfer.mapper.McpServerMapper;
import com.mcp.transfer.mapper.McpToolMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpAggregateServiceTest {

    @Mock private McpServerMapper mcpServerMapper;
    @Mock private McpToolMapper mcpToolMapper;
    @Mock private McpPromptMapper mcpPromptMapper;
    @Mock private McpResourceMapper mcpResourceMapper;
    @Mock private ToolAccessPolicy toolAccessPolicy;
    @Mock private McpService mcpService;
    @Mock private McpUpstreamClient mcpUpstreamClient;
    @Mock private McpSessionCache mcpSessionCache;
    @Mock private ToolBudgetAlerter toolBudgetAlerter;
    @Mock private ToolResultService toolResultService;

    @Spy
    private ToolSearchService toolSearchService = new ToolSearchService();

    @Spy
    private ToolExposureProperties toolExposureProperties = new ToolExposureProperties();

    @Spy
    private TokenEstimator tokenEstimator = new TokenEstimator();

    @InjectMocks
    private McpAggregateService service;

    private final McpServer pcb = server(10, "pcb-server", "pcb", "META");
    private final McpServer crm = server(11, "crm-server", "crm", "DIRECT");

    @BeforeEach
    void setUp() {
        AggregateScope.set(new AggregateScope.Scope(
                Set.of(10, 11), Map.of(10, "pcb", 11, "crm"), "/mcp/e/team", "Team"));
        ReflectionTestUtils.setField(toolSearchService, "properties", toolExposureProperties);
        lenient().when(mcpService.toToolDto(any(), any())).thenAnswer(invocation -> {
            McpTool tool = invocation.getArgument(1);
            com.mcp.transfer.dto.mcp.result.tools.McpTool dto =
                    new com.mcp.transfer.dto.mcp.result.tools.McpTool();
            dto.setName(tool.getCode());
            dto.setDescription("desc-" + tool.getCode());
            dto.setInputSchema(JSON.parseObject(
                    "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}"));
            return dto;
        });
    }

    @AfterEach
    void tearDown() {
        AggregateScope.clear();
        toolExposureProperties.setMetaEnabled(true);
    }

    @Test
    void resolvesNativeToolByLongestServerKeyPrefix() {
        stubServers(pcb);
        when(mcpToolMapper.selectOneByServerIdAndCode(10, "add")).thenReturn(tool(1, 10, "add"));

        McpAggregateService.Target target = service.resolveTarget("app", "pcb__add");

        assertNotNull(target);
        assertEquals("add", target.originalName);
        assertEquals(Integer.valueOf(1), target.tool.getId());
        assertEquals(Boolean.FALSE, target.proxy);
    }

    @Test
    void unknownPrefixReturnsNull() {
        stubServers(pcb);

        assertNull(service.resolveTarget("app", "other__add"));
    }

    @Test
    void endpointMetaModeListsHotToolsAndMetaToolsOnlyForMetaServer() {
        stubServers(pcb, crm);
        List<McpTool> pcbTools = List.of(
                tool(1, 10, "create"), tool(2, 10, "update"), tool(3, 10, "delete"));
        List<McpTool> crmTools = List.of(tool(4, 11, "list"));
        when(toolAccessPolicy.visibleTools(eq(pcb), eq("app"), eq(false))).thenReturn(pcbTools);
        when(toolAccessPolicy.visibleTools(eq(crm), eq("app"), eq(false))).thenReturn(crmTools);
        when(toolAccessPolicy.allTools(10)).thenReturn(pcbTools);
        when(toolAccessPolicy.allTools(11)).thenReturn(crmTools);
        when(toolAccessPolicy.hotTools(pcb, pcbTools)).thenReturn(List.of(pcbTools.get(0)));

        McpResponse response = handle("tools/list", null, null);
        McpToolsListResult result = (McpToolsListResult) response.getResult();

        List<String> names = names(result);
        assertTrue(names.contains("pcb__create"), "热工具应带前缀出现");
        assertTrue(names.contains("crm__list"), "DIRECT Server 的工具仍直出");
        assertTrue(names.contains("search_tools"));
        assertTrue(names.contains("get_tool_schema"));
        assertTrue(names.contains("call_tool"));
        assertTrue(names.contains("read_result_chunk"));
        assertFalse(names.contains("pcb__update"), "META Server 的非热工具不应直出");
        assertEquals(6, names.size());

        Map<?, ?> meta = result.getMeta();
        assertEquals("META", meta.get("exposureMode"));
        assertEquals(1, meta.get("metaServers"));
        assertEquals(1, meta.get("directServers"));
        assertEquals(1, meta.get("hotTools"));
        assertEquals(4, meta.get("visibleTools"));
        assertTrue(((Number) meta.get("visibleTokens")).longValue() > 0);
        assertTrue(((Number) meta.get("returnedTokens")).longValue() > 0);
        assertEquals(tokenEstimator.method(), meta.get("tokenizer"));
    }

    @Test
    void metaDisabledFallsBackToDirectAndListsAllTools() {
        toolExposureProperties.setMetaEnabled(false);
        stubServers(pcb);
        List<McpTool> pcbTools = List.of(tool(1, 10, "create"), tool(2, 10, "update"));
        when(toolAccessPolicy.visibleTools(eq(pcb), eq("app"), eq(false))).thenReturn(pcbTools);
        when(toolAccessPolicy.allTools(10)).thenReturn(pcbTools);

        McpToolsListResult result = (McpToolsListResult) handle("tools/list", null, null).getResult();

        List<String> names = names(result);
        assertEquals(List.of("pcb__create", "pcb__update"), names);
        assertEquals("DIRECT", result.getMeta().get("exposureMode"));
    }

    @Test
    void searchToolsReturnsPrefixedNamesAcrossServers() {
        stubServers(pcb, crm);
        List<McpTool> pcbTools = List.of(tool(1, 10, "create"));
        List<McpTool> crmTools = List.of(tool(4, 11, "list"));
        stubVisibility(pcbTools, crmTools);

        JSONObject args = new JSONObject();
        args.put("query", "order");
        McpResData data = (McpResData) handle("tools/call", "search_tools", args).getResult();
        JSONObject payload = JSON.parseObject(text(data));

        assertEquals("order", payload.getString("query"));
        assertEquals("BM25", payload.getString("mode"));
        assertEquals(2, payload.getIntValue("totalTools"));
        assertEquals(2, payload.getIntValue("returned"));
        Set<String> names = new HashSet<>();
        for (int i = 0; i < payload.getJSONArray("tools").size(); i++) {
            names.add(payload.getJSONArray("tools").getJSONObject(i).getString("name"));
        }
        assertTrue(names.contains("pcb__create"));
        assertTrue(names.contains("crm__list"));
    }

    @Test
    void searchToolsWithoutQueryReturnsGroupCatalog() {
        stubServers(pcb, crm);
        List<McpTool> pcbTools = List.of(tool(1, 10, "create"));
        List<McpTool> crmTools = List.of(tool(4, 11, "list"));
        stubVisibility(pcbTools, crmTools);

        JSONObject args = new JSONObject();
        McpResData data = (McpResData) handle("tools/call", "search_tools", args).getResult();
        JSONObject payload = JSON.parseObject(text(data));

        assertEquals("CATALOG", payload.getString("mode"));
        assertEquals(2, payload.getIntValue("totalTools"));
        assertEquals(2, payload.getJSONArray("groups").size());
        Set<String> domains = new HashSet<>();
        for (int i = 0; i < payload.getJSONArray("groups").size(); i++) {
            domains.add(payload.getJSONArray("groups").getJSONObject(i).getString("domain"));
        }
        assertTrue(domains.contains("pcb"));
        assertTrue(domains.contains("crm"));
    }

    @Test
    void searchToolsCatalogWithDomainListsTools() {
        stubServers(pcb, crm);
        List<McpTool> pcbTools = List.of(tool(1, 10, "create"));
        List<McpTool> crmTools = List.of(tool(4, 11, "list"));
        stubVisibility(pcbTools, crmTools);

        JSONObject args = new JSONObject();
        args.put("domain", "pcb");
        McpResData data = (McpResData) handle("tools/call", "search_tools", args).getResult();
        JSONObject payload = JSON.parseObject(text(data));

        assertEquals("CATALOG", payload.getString("mode"));
        assertEquals("pcb", payload.getString("domain"));
        assertEquals(1, payload.getJSONArray("tools").size());
        assertEquals("pcb__create", payload.getJSONArray("tools").getJSONObject(0).getString("name"));
    }

    @Test
    void getToolSchemaResolvesPrefixedName() {
        stubServers(pcb, crm);
        McpTool listTool = tool(4, 11, "list");
        when(mcpToolMapper.selectOneByServerIdAndCode(11, "list")).thenReturn(listTool);
        when(toolAccessPolicy.canUse(crm, "app", listTool)).thenReturn(true);

        JSONObject args = new JSONObject();
        args.put("name", "crm__list");
        McpResData data = (McpResData) handle("tools/call", "get_tool_schema", args).getResult();
        JSONObject payload = JSON.parseObject(text(data));

        assertEquals("crm__list", payload.getString("name"));
        assertNotNull(payload.getJSONObject("inputSchema"));
    }

    @Test
    void callToolRoutesToOriginalToolName() {
        stubServers(pcb, crm);
        McpTool listTool = tool(4, 11, "list");
        when(mcpToolMapper.selectOneByServerIdAndCode(11, "list")).thenReturn(listTool);
        when(toolAccessPolicy.canUse(crm, "app", listTool)).thenReturn(true);
        when(mcpService.executeToolOnServer(eq(crm), eq(listTool), any(), any())).thenReturn("ok");

        JSONObject arguments = new JSONObject();
        arguments.put("page", 1);
        JSONObject args = new JSONObject();
        args.put("name", "crm__list");
        args.put("arguments", arguments);
        args.put("reason", "用户要看订单列表");

        Object result = handle("tools/call", "call_tool", args).getResult();

        ArgumentCaptor<McpRequest> captor = ArgumentCaptor.forClass(McpRequest.class);
        verify(mcpService).executeToolOnServer(eq(crm), eq(listTool), captor.capture(), any());
        // reason 只用于审批/审计，不能混进目标工具参数
        assertEquals(1, captor.getValue().getParams().getArguments().getIntValue("page"));
        assertNull(captor.getValue().getParams().getArguments().get("reason"));
        assertTrue(result instanceof McpResData);
        assertEquals("ok", text((McpResData) result));
    }

    @Test
    void readResultChunkRejectsUnknownCallId() {
        stubServers(pcb, crm);

        McpResData expected = new McpResData();
        List<JSONObject> content = new ArrayList<>();
        JSONObject item = new JSONObject();
        item.put("type", "text");
        item.put("text", "结果不存在或已过期，请重新执行原工具。");
        content.add(item);
        expected.setContent(content);
        expected.setIsError(true);
        when(toolResultService.readChunk(eq("app"), any())).thenReturn(expected);

        JSONObject args = new JSONObject();
        args.put("call_id", "not-exists");
        McpResData result = (McpResData) handle("tools/call", "read_result_chunk", args).getResult();

        assertEquals(Boolean.TRUE, result.getIsError(), text(result));
        assertTrue(text(result).contains("不存在或已过期"));
    }

    @Test
    void longToolNameIsFittedAndResolvableByAlias() {
        stubServers(pcb);
        String code = "very_long_tool_code_that_would_exceed_the_model_limit_for_tool_names";
        McpTool longTool = tool(9, 10, code);
        when(toolAccessPolicy.visibleTools(eq(pcb), eq("app"), eq(false))).thenReturn(List.of(longTool));

        String exposed = AggregateNaming.exposeTool("pcb", code, 64);
        assertEquals(64, exposed.length());

        McpAggregateService.Target target = service.resolveTarget("app", exposed);

        assertNotNull(target);
        assertEquals(code, target.originalName);
        assertEquals(Integer.valueOf(9), target.tool.getId());
    }


    @Test
    void getToolSchemaDefaultsToCompact() {
        stubServers(pcb, crm);
        McpTool listTool = tool(4, 11, "list");
        when(mcpToolMapper.selectOneByServerIdAndCode(11, "list")).thenReturn(listTool);
        when(toolAccessPolicy.canUse(crm, "app", listTool)).thenReturn(true);

        JSONObject args = new JSONObject();
        args.put("name", "crm__list");
        JSONObject payload = JSON.parseObject(text((McpResData)
                handle("tools/call", "get_tool_schema", args).getResult()));

        assertEquals("compact", payload.getString("detailLevel"));
        JSONObject schema = payload.getJSONObject("inputSchema");
        assertNull(schema.getString("description"));
        assertNotNull(schema.getJSONObject("properties").getJSONObject("q"));
        assertTrue(payload.getIntValue("tokens") > 0);
    }

    @Test
    void getToolSchemaFullKeepsDescription() {
        stubServers(pcb, crm);
        McpTool listTool = tool(4, 11, "list");
        when(mcpToolMapper.selectOneByServerIdAndCode(11, "list")).thenReturn(listTool);
        when(toolAccessPolicy.canUse(crm, "app", listTool)).thenReturn(true);

        JSONObject args = new JSONObject();
        args.put("name", "crm__list");
        args.put("detail_level", "full");
        JSONObject payload = JSON.parseObject(text((McpResData)
                handle("tools/call", "get_tool_schema", args).getResult()));

        assertEquals("full", payload.getString("detailLevel"));
        assertEquals("desc-schema", payload.getJSONObject("inputSchema").getString("description"));
    }

    @Test
    void getToolSchemaBatchReturnsPerItemResults() {
        stubServers(pcb, crm);
        McpTool listTool = tool(4, 11, "list");
        when(mcpToolMapper.selectOneByServerIdAndCode(11, "list")).thenReturn(listTool);
        when(toolAccessPolicy.canUse(crm, "app", listTool)).thenReturn(true);

        JSONObject args = new JSONObject();
        args.put("tool_codes", new JSONArray(java.util.Arrays.asList("crm__list", "missing__x")));
        JSONObject payload = JSON.parseObject(text((McpResData)
                handle("tools/call", "get_tool_schema", args).getResult()));

        assertEquals(2, payload.getIntValue("requested"));
        assertEquals(2, payload.getJSONArray("schemas").size());
        assertEquals("crm__list",
                payload.getJSONArray("schemas").getJSONObject(0).getString("name"));
        assertNotNull(payload.getJSONArray("schemas").getJSONObject(0).getJSONObject("inputSchema"));
        assertNotNull(payload.getJSONArray("schemas").getJSONObject(1).getString("error"));
    }

    // ==================== helpers ====================

    private void stubServers(McpServer... servers) {
        List<McpServer> all = List.of(servers);
        when(mcpServerMapper.getAllByStatus(1)).thenReturn(all);
    }

    private void stubVisibility(List<McpTool> pcbTools, List<McpTool> crmTools) {
        when(toolAccessPolicy.visibleTools(eq(pcb), eq("app"), eq(false))).thenReturn(pcbTools);
        when(toolAccessPolicy.visibleTools(eq(crm), eq("app"), eq(false))).thenReturn(crmTools);
        when(toolAccessPolicy.allTools(10)).thenReturn(pcbTools);
        when(toolAccessPolicy.allTools(11)).thenReturn(crmTools);
    }

    private McpResponse handle(String method, String name, JSONObject args) {
        McpRequest request = new McpRequest();
        request.setId(1);
        request.setMethod(method);
        if (name != null) {
            Params params = new Params();
            params.setName(name);
            params.setArguments(args);
            request.setParams(params);
        }
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute("mcpAppId", "app");
        return service.handle(request, http);
    }

    private List<String> names(McpToolsListResult result) {
        List<String> names = new ArrayList<>();
        for (com.mcp.transfer.dto.mcp.result.tools.McpTool tool : result.getTools()) {
            names.add(tool.getName());
        }
        return names;
    }

    private String text(McpResData data) {
        return data.getContent().get(0).getString("text");
    }

    private McpServer server(int id, String name, String key, String exposure) {
        return new McpServer()
                .setId(id)
                .setTenantId(5)
                .setName(name)
                .setServerKey(key)
                .setPath("/mcp/v1/" + key)
                .setStatus(1)
                .setUpstreamType("NONE")
                .setToolAccessMode("ALL")
                .setToolExposureMode(exposure)
                .setUpdateTime(new Date(1_700_000_000_000L));
    }

    private McpTool tool(int id, int serverId, String code) {
        McpTool tool = new McpTool();
        tool.setId(id);
        tool.setServerId(serverId);
        tool.setCode(code);
        tool.setName(code);
        tool.setStatus(1);
        tool.setTags("order");
        tool.setTokenEstimate(120);
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", code);
        definition.put("description", "desc-" + code);
        definition.put("inputSchema", JSON.parseObject(
                "{\"type\":\"object\",\"description\":\"desc-schema\","
                        + "\"properties\":{\"q\":{\"type\":\"string\",\"description\":\"query\"}},"
                        + "\"required\":[\"q\"]}"));
        tool.setResJson(JSON.toJSONString(definition));
        return tool;
    }
}
