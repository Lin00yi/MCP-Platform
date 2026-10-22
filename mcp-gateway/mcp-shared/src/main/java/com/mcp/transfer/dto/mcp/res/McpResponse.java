package com.mcp.transfer.dto.mcp.res;

import com.alibaba.fastjson.annotation.JSONField;
import com.mcp.transfer.enums.McpErrorCode;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP协议顶层响应实体（JSON-RPC 2.0）
 * 对应Initialize响应的顶层结构
 */
@Data
public class McpResponse {
    /**
     * JSON-RPC版本（固定为2.0）
     */
    @JSONField(name = "jsonrpc")
    private String jsonrpc = "2.0";

    /**
     * 响应ID（与请求ID一致）
     */
    @JSONField(name = "id")
    private Object id;

    /**
     * 响应结果（成功时返回）
     */
    @JSONField(name = "result")
    private Object result;

    /**
     * 错误信息（失败时返回）
     */
    @JSONField(name = "error")
    private Object error;

    /** 兼容上游原始 error 对象 / JSONObject。 */
    public void setError(Object error) {
        this.error = error;
    }

    /**
     * 把枚举转成标准 JSON-RPC error 对象。
     * 之前直接序列化枚举会得到 "METHOD_NOT_FOUND" 字符串，标准 MCP 客户端无法解析。
     */
    public void setError(McpErrorCode errorCode) {
        if (errorCode == null) {
            this.error = null;
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", errorCode.getCode());
        body.put("message", errorCode.getMessage());
        body.put("data", errorCode.getDescription());
        this.error = body;
    }
}