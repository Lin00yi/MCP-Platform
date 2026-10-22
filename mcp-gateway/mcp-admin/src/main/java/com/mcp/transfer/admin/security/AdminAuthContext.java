package com.mcp.transfer.admin.security;

import com.mcp.transfer.enums.RoleEnum;
import lombok.Data;

import java.util.List;

/**
 * 后台当前登录用户上下文（ThreadLocal）
 */
@Data
public class AdminAuthContext {

    private static final ThreadLocal<AdminAuthContext> HOLDER = new ThreadLocal<>();

    private Long userId;
    private String username;
    private String role;
    private List<Integer> tenantIds;
    /** 签发 token 时用户记录的 update_time（毫秒），用于失效旧 token */
    private Long userUpdatedAt;

    public static void set(AdminAuthContext ctx) {
        HOLDER.set(ctx);
    }

    public static AdminAuthContext get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public boolean isSuperAdmin() {
        return RoleEnum.SUPER_ADMIN.getCode().equals(role);
    }

    public RoleEnum getRoleEnum() {
        return RoleEnum.getByCode(role);
    }

    /** 是否能访问指定业务方(租户)的数据 */
    public boolean canAccessTenant(Integer tenantId) {
        if (isSuperAdmin()) {
            return true;
        }
        return tenantIds != null && tenantIds.contains(tenantId);
    }
}
