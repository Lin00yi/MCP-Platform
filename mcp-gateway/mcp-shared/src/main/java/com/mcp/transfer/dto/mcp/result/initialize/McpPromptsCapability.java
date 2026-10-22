package com.mcp.transfer.dto.mcp.result.initialize;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.Data;

/**
 * 服务器提示模板能力声明
 */
@Data
public class McpPromptsCapability {
    /**
     * 是否支持提示模板列表变更通知
     */
    @JSONField(name = "listChanged")
    private Boolean listChanged;
}