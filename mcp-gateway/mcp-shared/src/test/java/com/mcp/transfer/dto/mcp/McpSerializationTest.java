package com.mcp.transfer.dto.mcp;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcp.transfer.dto.mcp.result.data.McpResData;
import com.mcp.transfer.dto.mcp.result.tools.McpTool;
import com.mcp.transfer.dto.mcp.result.tools.McpToolsListResult;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定 MCP 扩展字段的线上 JSON 名称：_meta / nextCursor / isError，
 * 避免注解与 Lombok getter 组合后序列化成别的名字。
 */
class McpSerializationTest {

    @Test
    void toolsListSerializesNextCursorAndMeta() {
        McpToolsListResult result = new McpToolsListResult();
        McpTool tool = new McpTool();
        tool.setName("order_query");
        result.setTools(Collections.singletonList(tool));
        result.setNextCursor("djE6MQ");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("visibleTools", 10);
        result.setMeta(meta);

        String fastjson = JSON.toJSONString(result);
        assertTrue(fastjson.contains("\"nextCursor\":\"djE6MQ\""), fastjson);
        assertTrue(fastjson.contains("\"_meta\""), fastjson);

        try {
            String jackson = new ObjectMapper().writeValueAsString(result);
            assertTrue(jackson.contains("\"nextCursor\":\"djE6MQ\""), jackson);
            assertTrue(jackson.contains("\"_meta\""), jackson);
            assertFalse(jackson.contains("\"meta\""), jackson);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void toolErrorSerializesIsError() {
        McpResData data = new McpResData();
        JSONObject item = new JSONObject();
        item.put("type", "text");
        item.put("text", "参数不合法");
        data.setContent(Collections.singletonList(item));
        data.setIsError(true);

        String fastjson = JSON.toJSONString(data);
        assertTrue(fastjson.contains("\"isError\":true"), fastjson);
        try {
            String jackson = new ObjectMapper().writeValueAsString(data);
            assertTrue(jackson.contains("\"isError\":true"), jackson);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
