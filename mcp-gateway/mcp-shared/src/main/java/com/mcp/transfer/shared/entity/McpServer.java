package com.mcp.transfer.shared.entity;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * MCP Server（对外 MCP 端点），归属业务方 tenant_id（N:1）。
 * path 为动态注册的对外地址；capabilities_json 为 initialize 能力声明。
 */
@Data
@Accessors(chain = true)
public class McpServer implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 自增id
     */
    private Integer id;

    /**
     * 归属业务方ID（tenant.id）
     */
    private Integer tenantId;

    /**
     * server 名称（对外展示）
     */
    private String name;

    /**
     * 稳定的机器标识（DSH serverName / 工具前缀）；创建后不可变
     */
    private String serverKey;

    /**
     * MCP 端点路径（动态注册）
     */
    private String path;

    /**
     * 是否启用 1启用(注册) 0禁用(下线)
     */
    private Integer status;

    /**
     * initialize 能力声明 JSON（protocolVersion/capabilities/serverInfo）
     */
    private String capabilitiesJson;

    /**
     * 上游类型：NONE=本地/REST 工具，MCP=代理到下游真实 MCP server
     */
    private String upstreamType;

    /**
     * 下游 MCP server 的 Streamable HTTP 地址（upstream_type=MCP 时）
     */
    private String upstreamUrl;

    /**
     * 内部身份断言的 audience；为空时默认 server-{id}
     */
    private String audience;

    /**
     * 下游 JWE 公钥（PEM/JWK）；identity.token-format=JWE 时必填
     */
    private String identityPublicKey;

    /**
     * 身份下传模式覆盖：OFF / OPTIONAL / REQUIRED；空或 INHERIT 表示沿用全局 mcp.identity.mode
     */
    private String identityMode;

    /**
     * 历史字段：应用授权已移除，固定为 ALL；仅为兼容存量库保留
     */
    private String toolAccessMode;

    /**
     * 工具暴露模式：DIRECT=全量 tools/list / META=元工具（search/get_tool_schema/call_tool）+ 热工具
     */
    private String toolExposureMode;

    /** 历史字段：应用授权已移除，固定为 ALL */
    private String promptAccessMode;

    /** 历史字段：应用授权已移除，固定为 ALL */
    private String resourceAccessMode;

    /**
     * META 模式常驻 tools/list 的工具 code（JSON 数组字符串）
     */
    private String hotToolCodes;

    /**
     * 工具数告警阈值；为空时使用全局默认
     */
    private Integer toolWarnThreshold;

    /**
     * 工具数硬阈值；为空时使用全局默认
     */
    private Integer toolMaxThreshold;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;
}
