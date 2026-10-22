package com.mcp.transfer.dto.mcp.result.initialize;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.Data;

/**
 * 服务器资源能力声明
 */
@Data
public class McpResourcesCapability {
    /**
     * 是否支持资源订阅
     */
    @JSONField(name = "subscribe")
    private Boolean subscribe;

    /**
     * 是否支持资源列表变更通知
     */
    @JSONField(name = "listChanged")
    private Boolean listChanged;
}