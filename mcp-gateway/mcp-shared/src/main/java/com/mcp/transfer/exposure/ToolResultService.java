package com.mcp.transfer.exposure;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mcp.transfer.shared.dto.mcp.result.data.McpResData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 超长工具结果的“截断 + 暂存 + 分段读取”（R9）。
 *
 * <p>当工具结果超过 {@code mcp.tools.result-spill-threshold} 字符时：
 * <ol>
 *   <li>全文写入 {@link ToolResultStore}，返回短 {@code call_id}；</li>
 *   <li>本次只返回前 N 字符预览 + 总长度 + read_result_chunk 调用指引；</li>
 *   <li>模型可继续用 read_result_chunk(call_id, offset, limit) 读取剩余内容。</li>
 * </ol>
 *
 * <p>只在 META 暴露模式下启用（否则顶层没有 read_result_chunk，截断后无法继续读取）；
 * DIRECT 模式仍沿用原有的 1MB 硬截断。
 */
@Component
public class ToolResultService {

    private static final int CHUNK_MAX = 20000;

    @Autowired
    private ToolExposureProperties properties;

    @Autowired
    private ToolResultStore store;

    @Autowired
    private AssetContentLimiter contentLimiter;

    /**
     * @param chunkable 当前 Server/Endpoint 是否提供 read_result_chunk
     */
    public Object present(String appId, String scope, Object result, boolean chunkable) {
        int threshold = properties.getResultSpillThreshold();
        if (!chunkable || threshold <= 0) {
            return contentLimiter.limitToolResult(result);
        }
        String text = extractText(result);
        if (text == null || text.length() <= threshold) {
            return contentLimiter.limitToolResult(result);
        }

        int maxChars = properties.getResultSpillMaxChars();
        boolean capped = maxChars > 0 && text.length() > maxChars;
        String stored = capped ? text.substring(0, maxChars) : text;
        String callId = store.put(appId, scope, stored, text.length(), capped);

        String preview = text.substring(0, Math.min(threshold, text.length()));
        StringBuilder hint = new StringBuilder(preview);
        hint.append("\n\n---\n[结果已截断] call_id=").append(callId)
                .append("，总长度=").append(text.length()).append(" 字符")
                .append("，本次预览=").append(preview.length()).append(" 字符");
        if (capped) {
            hint.append("（暂存上限 ").append(maxChars).append(" 字符，超出部分不会返回）");
        }
        hint.append("。\n继续读取请调用 read_result_chunk(call_id=\"")
                .append(callId).append("\", offset=").append(preview.length())
                .append(", limit=").append(defaultChunkLimit()).append(")。");
        return rebuild(result, hint.toString());
    }

    /** 处理 read_result_chunk 元工具调用。 */
    public McpResData readChunk(String appId, JSONObject args) {
        String callId = args == null ? null : args.getString("call_id");
        if (!StringUtils.hasText(callId)) {
            return errorResult("read_result_chunk 需要 call_id 参数");
        }
        ToolResultStore.Entry entry = store.get(appId, callId);
        if (entry == null) {
            return errorResult("结果不存在或已过期，请重新执行原工具。call_id=" + callId);
        }
        String text = entry.getText();
        int offset = args.getIntValue("offset");
        if (offset < 0) {
            offset = 0;
        }
        int limit = args.getIntValue("limit");
        if (limit <= 0) {
            limit = defaultChunkLimit();
        }
        limit = Math.min(limit, CHUNK_MAX);
        if (offset >= text.length()) {
            return textResult("[read_result_chunk] offset=" + offset + " 已到末尾，总长度="
                    + entry.getTotalChars() + " 字符。");
        }
        int end = Math.min(text.length(), offset + limit);
        String chunk = text.substring(offset, end);
        StringBuilder payload = new StringBuilder(chunk);
        payload.append("\n\n[chunk ").append(offset).append("-").append(end)
                .append(" / ").append(entry.getTotalChars()).append(" 字符]");
        if (end < text.length()) {
            payload.append(" 继续读取：read_result_chunk(call_id=\"").append(callId)
                    .append("\", offset=").append(end)
                    .append(", limit=").append(limit).append(")");
        } else {
            payload.append(" （已到末尾）");
        }
        return textResult(payload.toString());
    }

    private int defaultChunkLimit() {
        return Math.max(1, Math.min(properties.getChunkLimit(), CHUNK_MAX));
    }

    /** 只处理纯文本结果；图片/多模态内容不截断，交给原有 1MB 限制。 */
    private String extractText(Object result) {
        if (result instanceof String) {
            return (String) result;
        }
        if (result instanceof McpResData) {
            McpResData data = (McpResData) result;
            if (data.getContent() == null || data.getContent().isEmpty()) {
                return null;
            }
            StringBuilder text = new StringBuilder();
            for (JSONObject item : data.getContent()) {
                if (item == null || !"text".equals(item.getString("type"))) {
                    return null;
                }
                if (item.getString("text") != null) {
                    text.append(item.getString("text"));
                }
            }
            return text.toString();
        }
        if (result instanceof JSONObject || result instanceof JSONArray) {
            return JSON.toJSONString(result);
        }
        return null;
    }

    private Object rebuild(Object original, String text) {
        if (original instanceof McpResData) {
            McpResData data = new McpResData();
            List<JSONObject> content = new ArrayList<>();
            JSONObject item = new JSONObject();
            item.put("type", "text");
            item.put("text", text);
            content.add(item);
            data.setContent(content);
            data.setIsError(((McpResData) original).getIsError());
            return data;
        }
        return text;
    }

    private McpResData textResult(String text) {
        McpResData data = new McpResData();
        List<JSONObject> content = new ArrayList<>();
        JSONObject item = new JSONObject();
        item.put("type", "text");
        item.put("text", text);
        content.add(item);
        data.setContent(content);
        return data;
    }

    private McpResData errorResult(String message) {
        McpResData data = textResult(message);
        data.setIsError(true);
        return data;
    }
}
