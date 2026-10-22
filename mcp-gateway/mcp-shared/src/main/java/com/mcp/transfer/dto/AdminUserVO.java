package com.mcp.transfer.dto;

import com.mcp.transfer.entity.AdminUser;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 后台用户视图（不含密码）
 */
@Data
public class AdminUserVO {
    private Long id;
    private String username;
    private String name;
    private String role;
    private Integer status;
    private List<Integer> tenantIds;
    private Date createTime;

    public static AdminUserVO from(AdminUser u, List<Integer> tenantIds) {
        AdminUserVO vo = new AdminUserVO();
        vo.setId(u.getId());
        vo.setUsername(u.getUsername());
        vo.setName(u.getName());
        vo.setRole(u.getRole());
        vo.setStatus(u.getStatus());
        vo.setTenantIds(tenantIds);
        vo.setCreateTime(u.getCreateTime());
        return vo;
    }
}
