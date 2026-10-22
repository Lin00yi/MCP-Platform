package com.mcp.transfer.dto.mcp.result.initialize;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.Data;

/**
 * 服务器能力声明实体
 */
@Data
public class McpServerCapabilities {
    /**
     * 资源能力
     */
    @JSONField(name = "resources")
    private McpResourcesCapability resources;

    /**
     * 工具能力
     */
    @JSONField(name = "tools")
    private McpToolsCapability tools;

    /**
     * 提示模板能力
     */
    @JSONField(name = "prompts")
    private McpPromptsCapability prompts;

    /**
     * 日志输出能力（空对象）
     */
    //@JSONField(name = "logging")
    //private McpEmptyCapability logging = new McpEmptyCapability();
}