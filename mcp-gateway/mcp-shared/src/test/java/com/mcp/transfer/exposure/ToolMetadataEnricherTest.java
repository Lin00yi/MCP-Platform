package com.mcp.transfer.exposure;

import com.mcp.transfer.shared.entity.McpTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolMetadataEnricherTest {

    private ToolExposureProperties properties;
    private ToolMetadataEnricher enricher;

    @BeforeEach
    void setUp() {
        properties = new ToolExposureProperties();
        enricher = new ToolMetadataEnricher();
        ReflectionTestUtils.setField(enricher, "properties", properties);
    }

    @Test
    void appendsChineseKeywordsForEnglishCode() {
        McpTool tool = tool("order_query", "query orders", "list orders", "b2b");

        String description = enricher.description(tool);

        assertTrue(description.contains("订单"), description);
        assertTrue(description.contains("查询"), description);
    }

    @Test
    void tagsIncludeGeneratedEnglishAndChineseTerms() {
        McpTool tool = tool("refund_apply", "refund apply", "apply refund", "aftersales");

        List<String> tags = enricher.tags(tool);

        assertTrue(tags.contains("refund"));
        assertTrue(tags.contains("apply"));
        assertTrue(tags.contains("退款"));
        assertTrue(tags.contains("aftersales"));
    }

    @Test
    void splitsCamelCaseAndSnakeCase() {
        McpTool tool = tool("orderQuery", "orderQuery", "query", null);

        List<String> tags = enricher.tags(tool);

        assertTrue(tags.contains("order"));
        assertTrue(tags.contains("query"));
    }

    @Test
    void canBeDisabled() {
        properties.setMetadataEnrich(false);
        McpTool tool = tool("order_query", "query orders", "list orders", "b2b");

        assertEquals("list orders", enricher.description(tool));
        assertEquals(List.of("b2b"), enricher.tags(tool));
    }

    @Test
    void doesNotDuplicateExistingTerms() {
        McpTool tool = tool("order_query", "订单 查询", "订单查询 order query", "order");

        String description = enricher.description(tool);
        List<String> tags = enricher.tags(tool);

        assertFalse(description.contains("order order"));
        assertEquals(1, tags.stream().filter("order"::equalsIgnoreCase).count());
    }

    private McpTool tool(String code, String name, String description, String tags) {
        McpTool tool = new McpTool();
        tool.setId(1);
        tool.setServerId(10);
        tool.setCode(code);
        tool.setName(name);
        tool.setTags(tags);
        tool.setResJson("{\"name\":\"" + code + "\",\"description\":\"" + description
                + "\",\"inputSchema\":{\"type\":\"object\"}}");
        return tool;
    }
}
