package com.mcp.transfer.dto.mcp.result.initialize;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.Data;

/**
 * 服务器信息实体（必填）
 */
@Data
public class McpServerInfo {
    /**
     * 服务器程序名（必填）
     */
    @JSONField(name = "name")
    private String name;

    /**
     * 服务器版本号（必填）
     */
    @JSONField(name = "version")
    private String version;

    /**
     * 服务器显示名称（可选）
     */
    @JSONField(name = "title")
    private String title;
}