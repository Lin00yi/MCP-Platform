package com.mcp.transfer.dto.mcp.req;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.Data;

/**
 * 客户端信息实体（必填）
 */
@Data
public class McpClientInfo {
    /**
     * 客户端程序名（必填）
     */
    @JSONField(name = "name")
    private String name;

    /**
     * 客户端版本号（必填）
     */
    @JSONField(name = "version")
    private String version;

    /**
     * 客户端显示名称（可选）
     */
    @JSONField(name = "title")
    private String title;
}