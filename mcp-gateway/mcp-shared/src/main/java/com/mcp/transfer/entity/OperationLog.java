package com.mcp.transfer.entity;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 操作审计日志（谁在什么时候对什么资源做了什么）。
 */
@Data
@Accessors(chain = true)
public class OperationLog implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;

    /** 操作者用户名 */
    private String username;

    /** 归属业务方（用于租户隔离；平台级操作为 null） */
    private Integer tenantId;

    /** 操作类型：CREATE/UPDATE/DELETE/GRANT/REVOKE/APPROVE/REJECT/REFRESH */
    private String action;

    /** 资源类型：TENANT/SERVER/TOOL/PROMPT/RESOURCE/APP/SERVER_REL/TOOL_REL/GRANT_REQUEST */
    private String resourceType;

    /** 资源 ID（字符串，可能是多个） */
    private String resourceId;

    /** 详情（JSON） */
    private String detail;

    private Date createTime;
}
