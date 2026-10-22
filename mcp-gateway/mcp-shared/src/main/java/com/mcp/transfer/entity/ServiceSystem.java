package com.mcp.transfer.entity;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 系统服务定义（mcp_tool.system_id 关联，用于 SK_EXCHANGE 兜底 auth_url）
 */
@Data
@Accessors(chain = true)
public class ServiceSystem implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
    * 自增id（对应 mcp_tool.system_id）
    */
    private Integer id;

    /**
    * 系统名称
    */
    private String name;

    /**
    * 授权URL（用于 sk 换 token，POST 请求，参数 sk）
    */
    private String authUrl;

    /**
    * 状态 1、启用 0、禁用
    */
    private Integer status;

    /**
    * 创建时间
    */
    private Date createTime;

    /**
    * 更新时间
    */
    private Date updateTime;
}
