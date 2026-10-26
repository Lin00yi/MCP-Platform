package com.mcp.transfer.exposure;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mcp.transfer.shared.entity.McpTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolSearchServiceTest {

    private ToolSearchService service;

    private final List<McpTool> tools = Arrays.asList(
            tool(1, "order_query", "查询订单", "order,query", "按订单号查询订单详情"),
            tool(2, "order_create", "创建订单", "order", "创建一笔新订单"),
            tool(3, "refund_apply", "申请退款", "refund", "根据订单号发起退款"),
            tool(4, MetaToolFactory.SEARCH_TOOLS, "搜索工具", "meta", "元工具"));

    @BeforeEach
    void setUp() {
        service = new ToolSearchService();
        ReflectionTestUtils.setField(service, "properties", new ToolExposureProperties());
    }

    @Test
    void exactCodeRanksFirst() {
        List<ToolSearchService.ScoredTool> hits = service.search(tools, "order_query", null, 10);
        assertEquals("order_query", hits.get(0).getTool().getCode());
    }

    @Test
    void chineseKeywordMatchesDescription() {
        List<ToolSearchService.ScoredTool> hits = service.search(tools, "退款", null, 10);
        assertEquals(1, hits.size());
        assertEquals("refund_apply", hits.get(0).getTool().getCode());
    }

    @Test
    void tagFilterIsApplied() {
        List<ToolSearchService.ScoredTool> hits = service.search(tools, "订单", "refund", 10);
        assertEquals(1, hits.size());
        assertEquals("refund_apply", hits.get(0).getTool().getCode());
    }

    @Test
    void limitIsRespected() {
        List<ToolSearchService.ScoredTool> hits = service.search(tools, "订单", null, 1);
        assertEquals(1, hits.size());
    }

    @Test
    void metaToolsAreNeverReturned() {
        List<ToolSearchService.ScoredTool> hits = service.search(tools, "search_tools", null, 10);
        for (ToolSearchService.ScoredTool hit : hits) {
            assertTrue(!MetaToolFactory.isMetaTool(hit.getTool().getCode()));
        }
    }

    @Test
    void synonymExpandsChineseQueryToEnglishDescription() {
        McpTool refund = tool(6, "refund_order", "Refund", "", "refund order by order no");
        List<ToolSearchService.ScoredTool> hits = service.search(
                Arrays.asList(refund), "退款", null, 10);
        assertEquals("refund_order", hits.get(0).getTool().getCode());
    }

    @Test
    void cjkBigramFindsReorderedChinesePhrase() {
        McpTool order = tool(7, "get_order", "查询订单", "", "按订单号查询");
        List<ToolSearchService.ScoredTool> hits = service.search(
                Arrays.asList(order), "订单查询", null, 10);
        assertEquals("get_order", hits.get(0).getTool().getCode());
    }

    @Test
    void blankQueryReturnsFirstPage() {
        List<ToolSearchService.ScoredTool> hits = service.search(tools, "", null, 2);
        assertEquals(2, hits.size());
    }

    private McpTool tool(int id, String code, String name, String tags, String description) {
        return new McpTool()
                .setId(id)
                .setServerId(10)
                .setCode(code)
                .setName(name)
                .setTags(tags)
                .setResJson("{\"name\":\"" + code + "\",\"description\":\"" + description
                        + "\",\"inputSchema\":{\"type\":\"object\"}}");
    }

    @Test
    void parsesDomainTagsAndTopK() {
        JSONObject args = new JSONObject();
        args.put("domain", "pcb");
        args.put("tags", new JSONArray(Arrays.asList("order", "pcb")));
        args.put("top_k", 5);

        ToolSearchService.SearchRequest request =
                ToolSearchService.parseRequest(args, new ToolExposureProperties());

        assertEquals("pcb", request.getDomain());
        assertEquals(Arrays.asList("order", "pcb"), request.getTags());
        assertEquals(5, request.getTopK());
    }

    @Test
    void bm25RanksExactCodeFirst() {
        ToolSearchService.SearchRequest request = new ToolSearchService.SearchRequest();
        request.setQuery("order_query");
        request.setTopK(10);

        ToolSearchService.SearchResult result = service.searchDocuments(docs(tools), request);

        assertEquals(ToolSearchService.Mode.BM25, result.getMode());
        assertEquals("order_query", result.getHits().get(0).getDoc().getCode());
    }

    @Test
    void keywordModeCanBeForced() {
        properties().setSearchMode("KEYWORD");
        ToolSearchService.SearchRequest request = new ToolSearchService.SearchRequest();
        request.setQuery("退款");
        request.setTopK(10);

        ToolSearchService.SearchResult result = service.searchDocuments(docs(tools), request);

        assertEquals(ToolSearchService.Mode.KEYWORD, result.getMode());
        assertEquals("refund_apply", result.getHits().get(0).getDoc().getCode());
    }

    @Test
    void catalogModeSortsByDomainAndName() {
        List<ToolSearchService.ToolDoc> docs = new ArrayList<>();
        docs.add(ToolSearchService.ToolDoc.of(tool(1, "order_query", "查询订单", "order", "查询"), "crm__order_query", "crm"));
        docs.add(ToolSearchService.ToolDoc.of(tool(2, "pcb_quote", "PCB 报价", "pcb", "报价"), "pcb__pcb_quote", "pcb"));

        ToolSearchService.SearchRequest request = new ToolSearchService.SearchRequest();
        request.setTopK(10);

        ToolSearchService.SearchResult result = service.searchDocuments(docs, request);

        assertEquals(ToolSearchService.Mode.CATALOG, result.getMode());
        assertEquals("crm__order_query", result.getHits().get(0).getDoc().getExposedName());
        assertEquals("pcb__pcb_quote", result.getHits().get(1).getDoc().getExposedName());
    }

    @Test
    void domainFilterRestrictsResults() {
        List<ToolSearchService.ToolDoc> docs = new ArrayList<>();
        docs.add(ToolSearchService.ToolDoc.of(tool(1, "order_query", "查询订单", "order", "查询订单"), "crm__order_query", "crm"));
        docs.add(ToolSearchService.ToolDoc.of(tool(2, "order_create", "创建订单", "order", "创建订单"), "pcb__order_create", "pcb"));

        ToolSearchService.SearchRequest request = new ToolSearchService.SearchRequest();
        request.setQuery("订单");
        request.setDomain("pcb");
        request.setTopK(10);

        ToolSearchService.SearchResult result = service.searchDocuments(docs, request);

        assertEquals(1, result.getHits().size());
        assertEquals("pcb__order_create", result.getHits().get(0).getDoc().getExposedName());
    }

    @Test
    void hybridFusesBm25AndVectorWithRrf() {
        properties().setSearchMode("HYBRID");
        ToolEmbeddingService embedding = mock(ToolEmbeddingService.class);
        when(embedding.enabled()).thenReturn(true);
        when(embedding.queryVector("money back")).thenReturn(new float[]{1f, 0f});
        when(embedding.vector(10, 1)).thenReturn(new float[]{1f, 0f});
        when(embedding.vector(10, 2)).thenReturn(new float[]{0f, 1f});
        service.setEmbeddingService(embedding);

        McpTool refund = tool(1, "refund_order", "Refund", "refund", "money back guarantee");
        McpTool create = tool(2, "order_create", "Create order", "order", "create an order");

        ToolSearchService.SearchRequest request = new ToolSearchService.SearchRequest();
        request.setQuery("money back");
        request.setTopK(10);

        ToolSearchService.SearchResult result = service.searchDocuments(docs(Arrays.asList(refund, create)), request);

        assertEquals(ToolSearchService.Mode.HYBRID, result.getMode());
        assertEquals("refund_order", result.getHits().get(0).getDoc().getCode());
    }


    @Test
    void enrichmentLetsChineseQueryMatchEnglishOnlyTool() {
        ToolMetadataEnricher enricher = new ToolMetadataEnricher();
        ReflectionTestUtils.setField(enricher, "properties", properties());
        ReflectionTestUtils.setField(service, "metadataEnricher", enricher);

        McpTool englishOnly = tool(9, "order_query", "query orders", "", "list orders");
        ToolSearchService.SearchRequest request = new ToolSearchService.SearchRequest();
        request.setQuery("订单");
        request.setTopK(10);

        ToolSearchService.SearchResult result = service.searchDocuments(
                docs(java.util.Collections.singletonList(englishOnly)), request);

        assertEquals(1, result.getHits().size());
        assertEquals("order_query", result.getHits().get(0).getDoc().getCode());
    }

    private ToolExposureProperties properties() {
        return (ToolExposureProperties) ReflectionTestUtils.getField(service, "properties");
    }

    private List<ToolSearchService.ToolDoc> docs(List<McpTool> tools) {
        List<ToolSearchService.ToolDoc> docs = new ArrayList<>();
        for (McpTool tool : tools) {
            docs.add(ToolSearchService.ToolDoc.of(tool, tool.getCode(), "s" + tool.getServerId()));
        }
        return docs;
    }
}
