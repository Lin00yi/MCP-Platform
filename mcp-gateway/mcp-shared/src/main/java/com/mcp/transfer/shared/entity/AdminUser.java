package com.mcp.transfer.shared.entity;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 后台管理用户
 */
@Data
@Accessors(chain = true)
public class AdminUser implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 自增id */
    private Long id;

    /** 登录名 */
    private String username;

    /** BCrypt 密码 */
    private String password;

    /** 显示名 */
    private String name;

    /** 角色 SUPER_ADMIN/ADMIN/DEVELOPER/OPERATOR/USER */
    private String role;

    /** 1启用 0禁用 */
    private Integer status;

    private Date createTime;

    private Date updateTime;
}
