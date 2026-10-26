package com.mcp.transfer.exposure;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.mcp.transfer.shared.dto.mcp.result.tools.McpTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ToolSchemaTrimmerTest {

    private ToolSchemaTrimmer trimmer;

    private ToolExposureProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ToolExposureProperties();
        properties.setMaxDescriptionLength(20);
        properties.setTrimSchema(true);
        properties.setIncludeServerMeta(false);
        trimmer = new ToolSchemaTrimmer();
        ReflectionTestUtils.setField(trimmer, "properties", properties);
    }

    @Test
    void truncatesDescriptionAndRemovesExamplesRecursively() {
        McpTool tool = new McpTool();
        tool.setName("order_query");
        tool.setDescription("这是一个非常长的描述，用来验证超长描述会被截断，避免 tools/list 占用过多 token。");
        JSONObject schema = JSON.parseObject("{\"type\":\"object\",\"examples\":[{\"a\":1}],"
                + "\"properties\":{\"orderNo\":{\"type\":\"string\",\"examples\":[\"A1\"]},"
                + "\"items\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"examples\":[{}]}}},"
                + "\"required\":[\"orderNo\"]}");
        tool.setInputSchema(schema);
        JSONObject server = new JSONObject();
        server.put("id", 1);
        tool.setServer(server);

        McpTool trimmed = trimmer.trim(tool);

        assertEquals(20, trimmed.getDescription().length());
        assertNull(trimmed.getServer());
        JSONObject trimmedSchema = trimmed.getInputSchema();
        assertNotNull(trimmedSchema);
        assertFalse(trimmedSchema.containsKey("examples"));
        assertFalse(trimmedSchema.getJSONObject("properties").getJSONObject("orderNo").containsKey("examples"));
        assertFalse(trimmedSchema.getJSONObject("properties").getJSONObject("items")
                .getJSONObject("items").containsKey("examples"));
        // 校验语义不受影响
        assertEquals("object", trimmedSchema.getString("type"));
        assertEquals("orderNo", trimmedSchema.getJSONArray("required").getString(0));
    }

    @Test
    void keepsSchemaWhenTrimDisabled() {
        properties.setTrimSchema(false);
        McpTool tool = new McpTool();
        tool.setName("t");
        tool.setInputSchema(JSON.parseObject("{\"type\":\"object\",\"examples\":[{\"a\":1}]}"));

        McpTool trimmed = trimmer.trim(tool);

        assertNotNull(trimmed.getInputSchema());
        assertEquals(1, trimmed.getInputSchema().getJSONArray("examples").size());
    }
}
