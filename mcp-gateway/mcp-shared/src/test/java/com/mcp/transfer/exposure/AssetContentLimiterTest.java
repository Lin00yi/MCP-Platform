package com.mcp.transfer.exposure;

import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetContentLimiterTest {

    private AssetContentLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new AssetContentLimiter();
        AssetProperties properties = new AssetProperties();
        properties.setPromptMaxBytes(32);
        properties.setResourceMaxBytes(32);
        properties.setToolResultMaxBytes(32);
        ReflectionTestUtils.setField(limiter, "properties", properties);
    }

    @Test
    void truncatesUtf8Safely() {
        String text = "订单查询" + new String(new char[20]).replace('\0', 'x');
        String cut = limiter.truncate(text, 16, "test");
        assertTrue(text.getBytes(StandardCharsets.UTF_8).length > 16);
        assertTrue(cut.startsWith("订单查询"));
        assertTrue(cut.contains("truncated"));
    }

    @Test
    void limitsNestedPromptMessages() {
        JSONObject result = new JSONObject();
        result.put("messages", new com.alibaba.fastjson.JSONArray());
        JSONObject msg = new JSONObject();
        msg.put("text", new String(new char[200]).replace('\0', 'a'));
        result.getJSONArray("messages").add(msg);
        limiter.limitPrompt(result);
        assertTrue(msg.getString("text").contains("truncated"));
    }
}
