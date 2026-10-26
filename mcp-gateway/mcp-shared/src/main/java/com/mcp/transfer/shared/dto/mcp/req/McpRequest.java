package com.mcp.transfer.shared.dto.mcp.req;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.Data;

/**
 * MCP协议顶层请求实体（JSON-RPC 2.0）
 * 对应Initialize请求的顶层结构
 */
@Data
public class McpRequest {
    /**
     * JSON-RPC版本（固定为2.0）
     */
    @JSONField(name = "jsonrpc")
    private String jsonrpc = "2.0";

    /**
     * 请求ID（客户端生成，用于匹配响应）
     */
    @JSONField(name = "id")
    private Object id;

    /**
     * 请求方法名（Initialize请求固定为"initialize"）
     */
    @JSONField(name = "method")
    private String method;

    /**
     * Initialize请求参数
     */
    @JSONField(name = "params")
    private Params params;
}