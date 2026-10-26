package com.mcp.transfer.shared.dto.mcp.result.initialize;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.Data;

/**
 * 服务器工具能力声明
 */
@Data
public class McpToolsCapability {
    /**
     * 是否支持工具列表变更通知
     */
    @JSONField(name = "listChanged")
    private Boolean listChanged;
}