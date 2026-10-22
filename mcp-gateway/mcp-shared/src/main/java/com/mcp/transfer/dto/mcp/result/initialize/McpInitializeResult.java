package com.mcp.transfer.dto.mcp.result.initialize;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.Data;

/**
 * Initialize响应的result实体
 */
@Data
public class McpInitializeResult {
    /**
     * 服务器选择的MCP协议版本（必填，如2024-11-05）
     */
    @JSONField(name = "protocolVersion")
    private String protocolVersion;

    /**
     * 服务器能力声明
     */
    @JSONField(name = "capabilities")
    private McpServerCapabilities capabilities;

    /**
     * 服务器信息（必填）
     */
    @JSONField(name = "serverInfo")
    private McpServerInfo serverInfo;

    /**
     * 服务器使用说明（可选）
     */
    @JSONField(name = "instructions")
    private String instructions;
}