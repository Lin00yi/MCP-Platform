package com.mcp.transfer.dto.mcp.req;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.annotation.JSONField;
import lombok.Data;


/**
 * Initialize请求的params参数实体
 */
@Data
public class Params {
    /**
     * 客户端支持的MCP协议版本（必填，如2024-11-05）
     */
    @JSONField(name = "protocolVersion")
    private String protocolVersion;

    /**
     * 客户端能力声明
     */
    @JSONField(name = "capabilities")
    private McpClientCapabilities capabilities;

    /**
     * 客户端信息（必填）
     */
    @JSONField(name = "clientInfo")
    private McpClientInfo clientInfo;

    /**
     * 查询的方法
     */
    @JSONField(name = "name")
    private String name;

    /**
     * 查询数据
     */
    @JSONField(name = "arguments")
    private JSONObject arguments;

    /**
     * resources/read 的资源 URI
     */
    @JSONField(name = "uri")
    private String uri;

    /**
     * tools/list 分页游标（MCP 标准 cursor，不透明字符串）
     */
    @JSONField(name = "cursor")
    private String cursor;

}