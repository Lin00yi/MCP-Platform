package com.mcp.transfer.shared.entity;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 后台用户-业务方(租户)关联，一对多
 */
@Data
@Accessors(chain = true)
public class AdminUserTenant implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long userId;

    private Integer tenantId;

    private Date createTime;
}
