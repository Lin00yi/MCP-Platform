package com.mcp.transfer.shared.dto.mcp.req;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.Data;

/**
 * 客户端根目录能力声明
 */
@Data
public class McpRootsCapability {
    /**
     * 是否支持根目录变更通知
     */
    @JSONField(name = "listChanged")
    private Boolean listChanged;
}