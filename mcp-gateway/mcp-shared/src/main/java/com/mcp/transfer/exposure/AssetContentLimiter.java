package com.mcp.transfer.exposure;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mcp.transfer.dto.mcp.result.data.McpResData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 内容大小限制：Prompt 正文、Resource 内容、Tool 结果统一截断。
 * 列表元数据很小，真正会撑爆上下文的是这些按需读取的正文。
 */
@Component
public class AssetContentLimiter {

    private static final Logger logger = LoggerFactory.getLogger(AssetContentLimiter.class);

    @Autowired
    private AssetProperties properties;

    public JSONObject limitPrompt(JSONObject result) {
        return limitJson(result, properties.getPromptMaxBytes(), "prompt");
    }

    public JSONObject limitResource(JSONObject result) {
        return limitJson(result, properties.getResourceMaxBytes(), "resource");
    }

    public Object limitToolResult(Object result) {
        int max = properties.getToolResultMaxBytes();
        if (result instanceof McpResData) {
            McpResData data = (McpResData) result;
            if (data.getContent() != null) {
                for (JSONObject item : data.getContent()) {
                    limitObjectField(item, "text", max, "tool");
                }
            }
            return data;
        }
        if (result instanceof String) {
            return truncate((String) result, max, "tool");
        }
        if (result instanceof JSONObject) {
            return limitJson((JSONObject) result, max, "tool");
        }
        return result;
    }

    public JSONObject limitJson(JSONObject root, int maxBytes, String kind) {
        if (root == null || maxBytes <= 0) {
            return root;
        }
        limitNode(root, maxBytes, kind);
        return root;
    }

    private void limitNode(Object node, int maxBytes, String kind) {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            for (String key : new String[]{"text", "blob"}) {
                Object value = obj.get(key);
                if (value instanceof String) {
                    obj.put(key, truncate((String) value, maxBytes, kind));
                }
            }
            for (Object value : obj.values()) {
                limitNode(value, maxBytes, kind);
            }
        } else if (node instanceof JSONArray) {
            for (Object value : (JSONArray) node) {
                limitNode(value, maxBytes, kind);
            }
        }
    }

    private void limitObjectField(JSONObject obj, String key, int maxBytes, String kind) {
        Object value = obj.get(key);
        if (value instanceof String) {
            obj.put(key, truncate((String) value, maxBytes, kind));
        }
    }

    /** 按 UTF-8 字节截断，保证不会切断多字节字符 */
    public String truncate(String text, int maxBytes, String kind) {
        if (text == null || maxBytes <= 0) {
            return text;
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (text.substring(0, mid).getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        String cut = text.substring(0, low);
        logger.warn("[ASSET] {} 内容超限，已截断: {} -> {} bytes", kind, bytes.length, maxBytes);
        return cut + "\n...[truncated: original " + bytes.length + " bytes, limit " + maxBytes + " bytes]";
    }
}
