package com.mcp.transfer.shared.entity;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 业务方（租户），与 MCP Server 分离：一个业务方可拥有多个 MCP Server。
 */
@Data
@Accessors(chain = true)
public class Tenant implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 自增id
     */
    private Integer id;

    /**
     * 业务方名称
     */
    private String name;

    /**
     * 是否启用 1启用 0禁用
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
