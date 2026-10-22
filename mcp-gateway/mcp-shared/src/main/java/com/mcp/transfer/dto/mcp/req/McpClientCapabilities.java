package com.mcp.transfer.dto.mcp.req;

import com.alibaba.fastjson.annotation.JSONField;
import com.mcp.transfer.dto.mcp.result.initialize.McpEmptyCapability;
import lombok.Data;

/**
 * 客户端能力声明实体
 */
@Data
public class McpClientCapabilities {
    /**
     * 根目录能力
     */
    @JSONField(name = "roots")
    private McpRootsCapability roots;

    /**
     * LLM采样能力（空对象）
     */
    @JSONField(name = "sampling")
    private McpEmptyCapability sampling = new McpEmptyCapability();

    /**
     * 用户询问能力（空对象）
     */
    @JSONField(name = "elicitation")
    private McpEmptyCapability elicitation = new McpEmptyCapability();

    /**
     * 实验性功能
     */
    @JSONField(name = "experimental")
    private McpExperimentalCapability experimental;
}