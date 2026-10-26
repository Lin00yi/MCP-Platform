package com.mcp.transfer.shared.entity;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * MCP resource 定义（归属 server）。
 */
@Data
@Accessors(chain = true)
public class McpResource implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 自增id
     */
    private Integer id;

    /**
     * 归属 server ID（mcp_server.id）
     */
    private Integer serverId;

    /**
     * 资源 URI（MCP 协议用）
     */
    private String uri;

    /**
     * 资源名称
     */
    private String name;

    /**
     * 状态 1启用 0禁用
     */
    private Integer status;

    /**
     * 资源描述 + 模板定义 JSON
     */
    private String resJson;

    /**
     * 标签（逗号分隔）
     */
    private String tags;

    /**
     * 版本号
     */
    private String version;

    /** 预计算 token 估算 */
    private Integer tokenEstimate;

    /** tokenizer 标识 */
    private String tokenizer;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;
}
