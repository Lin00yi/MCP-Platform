package com.mcp.transfer.entity;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * MCP 工具定义（归属 server）。
 */
@Data
@Accessors(chain = true)
public class McpTool implements Serializable {
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
     * 接口说明
     */
    private String name;

    /**
     * 接口标识（唯一，即 MCP 工具名）
     */
    private String code;

    /**
     * 执行器类型：0本地（反射调用） 1HTTP转发
     */
    private Integer type;

    /**
     * 关联系统服务 service_system.id（SK_EXCHANGE 兜底 auth_url）
     */
    private Integer systemId;

    /**
     * 状态 1启用 0禁用
     */
    private Integer status;

    /**
     * 服务地址（本地写 class.method，远程写请求地址）
     */
    private String serviceUrl;

    /**
     * 请求方式 GET/POST（远程调用时使用）
     */
    private String requestType;

    /**
     * 是否需要token 0否 1是（远程调用时使用）
     */
    private Integer needToken;

    /**
     * POST请求参数形式 0:JSON body 1:x-www-form-urlencoded
     */
    private Integer bodyType;

    /**
     * 鉴权方式 NONE/SK_EXCHANGE/API_KEY/BEARER
     */
    private String authType;

    /**
     * 鉴权配置JSON
     */
    private String authConfig;

    /**
     * 参数映射JSON（可视化字段映射）
     */
    private String paramMapping;

    /**
     * 工具描述 + inputSchema
     */
    private String resJson;

    /**
     * 标签（逗号分隔，用于资产目录检索）
     */
    private String tags;

    /**
     * 版本号
     */
    private String version;

    /**
     * 预计算 token 估算（按 tool JSON 序列化结果）
     */
    private Integer tokenEstimate;

    /**
     * tokenizer 标识（如 cjk-aware/approx-v1）
     */
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
