package com.mcp.transfer.shared.dto.mcp.result.tools;

import com.alibaba.fastjson.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 工具列表Result节点（MCP tools/list）。
 */
@Data
public class McpToolsListResult {

    /**
     * 工具列表
     */
    @JSONField(name = "tools")
    private List<McpTool> tools;

    /**
     * MCP 标准分页游标：非空表示还有下一页，客户端应带 cursor 再请求。
     */
    @JSONField(name = "nextCursor")
    @JsonProperty("nextCursor")
    private String nextCursor;

    /**
     * 网关扩展元数据（客户端可忽略）：总量、暴露模式、预算告警等。
     * MCP 结果扩展字段规范名称为 _meta，Jackson（HTTP 出口）与 fastjson 都要锁定。
     */
    @JSONField(name = "_meta")
    @JsonProperty("_meta")
    private Map<String, Object> meta;
}
