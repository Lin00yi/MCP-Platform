package com.mcp.transfer.dto;

import lombok.Data;

import java.util.List;

/**
 * 后台用户创建/更新请求
 */
@Data
public class AdminUserRequest {
    private String username;
    /** 创建时必填；更新时可留空表示不改密码 */
    private String password;
    private String name;
    private String role;
    private Integer status;
    /** 分配的业务方(租户)ID 列表（一对多） */
    private List<Integer> tenantIds;
}
