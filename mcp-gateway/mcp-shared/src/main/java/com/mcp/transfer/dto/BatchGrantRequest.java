package com.mcp.transfer.dto;

import lombok.Data;

import java.util.List;

/**
 * 批量授权请求：为某个应用一次性授权多个 MCP Server。
 */
@Data
public class BatchGrantRequest {

    /**
     * 应用ID（对应 enn_app_info.app_id）
     */
    private String appId;

    /**
     * MCP Server ID 列表（对应 mcp_server.id）
     */
    private List<Integer> serverIds;
}
