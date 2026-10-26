package com.mcp.transfer.shared.enums;

import lombok.Getter;

/**
 * 后台管理角色（权限从高到低）
 */
@Getter
public enum RoleEnum {

    /** 平台超管：全局，所有业务方，所有权限 */
    SUPER_ADMIN("SUPER_ADMIN", "平台超管", true),

    /** 管理员：本业务方全量（含用户管理），不含租户管理 */
    ADMIN("ADMIN", "管理员", false),

    /** 开发者：本业务方技术配置（方法/系统服务/审批） */
    DEVELOPER("DEVELOPER", "开发者", false),

    /** 运维/操作者：本业务方只读 + 刷新 + 导出 */
    OPERATOR("OPERATOR", "运维", false),

    /** 普通用户：本业务方只读 */
    USER("USER", "用户", false);

    private final String code;
    private final String name;
    /** 是否平台级（跨业务方） */
    private final boolean platform;

    RoleEnum(String code, String name, boolean platform) {
        this.code = code;
        this.name = name;
        this.platform = platform;
    }

    public static RoleEnum getByCode(String code) {
        if (code == null) {
            return null;
        }
        for (RoleEnum r : values()) {
            if (r.code.equals(code)) {
                return r;
            }
        }
        return null;
    }

    // ---- 权限判断 ----

    /** 查看概览/日志 */
    public boolean canView() {
        return true;
    }

    /** 导出日志 */
    public boolean canExport() {
        return this != USER;
    }

    /** 刷新动态端点 */
    public boolean canRefresh() {
        return this != USER;
    }

    /** 管理方法定义 */
    public boolean canManageMethod() {
        return this == SUPER_ADMIN || this == ADMIN || this == DEVELOPER;
    }

    /** 管理系统服务 */
    public boolean canManageSystem() {
        return this == SUPER_ADMIN || this == ADMIN || this == DEVELOPER;
    }

    /** 审批 / 拓扑等敏感写操作（开发者及以上） */
    public boolean canManagePermission() {
        return this == SUPER_ADMIN || this == ADMIN || this == DEVELOPER;
    }

    /** 管理用户 */
    public boolean canManageUser() {
        return this == SUPER_ADMIN || this == ADMIN;
    }

    /** 维护全局访问拓扑（Namespace / Endpoint） */
    public boolean canManageTopology() {
        return this == SUPER_ADMIN || this == ADMIN;
    }

    /** 管理租户(业务方) */
    public boolean canManageTenant() {
        return this == SUPER_ADMIN;
    }
}
