package com.mcp.transfer.shared.dto.mcp.req;

import com.alibaba.fastjson.annotation.JSONField;
import com.mcp.transfer.shared.dto.mcp.result.initialize.McpEmptyCapability;
import lombok.Data;

/**
 * 客户端实验性功能声明
 */
@Data
public class McpExperimentalCapability {
    /**
     * 自定义功能（空对象）
     */
    @JSONField(name = "customFeature")
    private McpEmptyCapability customFeature = new McpEmptyCapability();
}