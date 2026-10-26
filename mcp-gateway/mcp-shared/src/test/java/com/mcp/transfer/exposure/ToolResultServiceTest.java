package com.mcp.transfer.exposure;

import com.alibaba.fastjson.JSONObject;
import com.mcp.transfer.shared.dto.mcp.result.data.McpResData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultServiceTest {

    private static final Pattern CALL_ID = Pattern.compile("call_id=([0-9a-f]{32})");

    private ToolExposureProperties properties;
    private ToolResultStore store;
    private ToolResultService service;

    @BeforeEach
    void setUp() {
        properties = new ToolExposureProperties();
        properties.setResultSpillThreshold(100);
        properties.setResultSpillMaxChars(10_000);
        properties.setChunkLimit(50);

        store = new ToolResultStore();
        ReflectionTestUtils.setField(store, "properties", properties);

        AssetContentLimiter limiter = new AssetContentLimiter();
        ReflectionTestUtils.setField(limiter, "properties", new AssetProperties());

        service = new ToolResultService();
        ReflectionTestUtils.setField(service, "properties", properties);
        ReflectionTestUtils.setField(service, "store", store);
        ReflectionTestUtils.setField(service, "contentLimiter", limiter);
    }

    @Test
    void shortResultIsReturnedUnchanged() {
        McpResData data = text("short");
        Object presented = service.present("app", "server:1", data, true);
        assertSame(data, presented);
    }

    @Test
    void longResultIsSpilledWithCallIdAndPreview() {
        String text = repeat('A', 250);
        McpResData presented = (McpResData) service.present("app", "server:1", text(text), true);
        String body = presented.getContent().get(0).getString("text");

        assertTrue(body.startsWith(repeat('A', 100)));
        assertTrue(body.contains("call_id="));
        assertTrue(body.contains("总长度=250"));
        assertTrue(body.contains("read_result_chunk"));
        assertNotNull(callId(body));
    }

    @Test
    void readChunkReturnsRemainingContentAndNextHint() {
        String text = repeat('B', 250);
        McpResData presented = (McpResData) service.present("app", "server:1", text(text), true);
        String callId = callId(presented.getContent().get(0).getString("text"));

        JSONObject first = new JSONObject();
        first.put("call_id", callId);
        first.put("offset", 100);
        first.put("limit", 50);
        String firstChunk = service.readChunk("app", first).getContent().get(0).getString("text");
        assertTrue(firstChunk.startsWith(repeat('B', 50)));
        assertTrue(firstChunk.contains("chunk 100-150"));
        assertTrue(firstChunk.contains("继续读取"));

        JSONObject last = new JSONObject();
        last.put("call_id", callId);
        last.put("offset", 200);
        last.put("limit", 50);
        String lastChunk = service.readChunk("app", last).getContent().get(0).getString("text");
        assertTrue(lastChunk.startsWith(repeat('B', 50)));
        assertTrue(lastChunk.contains("已到末尾"));
    }

    @Test
    void unknownOrForeignCallIdIsRejected() {
        String text = repeat('C', 250);
        McpResData presented = (McpResData) service.present("app", "server:1", text(text), true);
        String callId = callId(presented.getContent().get(0).getString("text"));

        JSONObject args = new JSONObject();
        args.put("call_id", callId);

        McpResData foreign = service.readChunk("other-app", args);
        assertEquals(Boolean.TRUE, foreign.getIsError());

        args.put("call_id", "not-exists");
        McpResData missing = service.readChunk("app", args);
        assertEquals(Boolean.TRUE, missing.getIsError());
    }

    @Test
    void chunkableFalseKeepsLegacyBehaviour() {
        String text = repeat('D', 250);
        McpResData presented = (McpResData) service.present("app", "server:1", text(text), false);
        String body = presented.getContent().get(0).getString("text");

        assertFalse(body.contains("call_id="));
        assertEquals(text, body);
    }

    @Test
    void stringResultIsSpilledAsText() {
        Object presented = service.present("app", "server:1", repeat('E', 250), true);
        assertTrue(presented instanceof String);
        assertTrue(((String) presented).contains("call_id="));
    }

    private McpResData text(String value) {
        McpResData data = new McpResData();
        List<JSONObject> content = new ArrayList<>();
        JSONObject item = new JSONObject();
        item.put("type", "text");
        item.put("text", value);
        content.add(item);
        data.setContent(content);
        return data;
    }

    private String callId(String body) {
        Matcher matcher = CALL_ID.matcher(body);
        assertTrue(matcher.find(), "结果中应包含 call_id");
        return matcher.group(1);
    }

    private String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
