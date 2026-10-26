package com.mcp.transfer.admin.security;

import com.mcp.transfer.shared.enums.RoleEnum;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 后台管理接口权限矩阵。
 *
 * <p>设计原则：<b>默认拒绝</b>。只有明确登记的「方法 + 路径前缀」才允许访问，
 * 未登记的写操作一律 403，避免新增接口时因忘记加判断而默认放行。
 *
 * <p>GET 视角与写操作分开：GET 只要求 canView（只读角色可见），但密钥类资源
 * （tools / apps / service-systems）要求对应的管理权限。
 */
public final class AdminPermissionEvaluator {

    private AdminPermissionEvaluator() {
    }

    /** 登录接口在拦截器里单独放行，这里不处理 */
    public static boolean isPublic(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.endsWith("/mcp/admin/auth/login");
    }

    /**
     * @param method HTTP 方法
     * @param path   请求路径（requestURI）
     * @param role   当前登录角色
     * @return 是否允许
     */
    public static boolean check(String method, String path, RoleEnum role) {
        if (role == null) {
            return false;
        }
        String m = method == null ? "" : method.toUpperCase();
        if ("OPTIONS".equals(m)) {
            return true;
        }
        if (!"GET".equals(m)) {
            return checkWrite(path, role);
        }
        return checkRead(path, role);
    }

    private static boolean checkRead(String path, RoleEnum role) {
        // 顺序敏感：先匹配更具体的前缀
        if (path.contains("/mcp/admin/approvals/policy")) {
            // 策略矩阵用于前端渲染按钮，所有登录角色可读
            return role.canView();
        }
        if (path.contains("/mcp/admin/approvals")) {
            // 列表/详情：开发者只能看到自己提交的（服务端再按 scope 限制）
            return role.canManagePermission();
        }
        if (path.contains("/mcp/admin/users")) {
            return role.canManageUser();
        }
        if (path.contains("/mcp/log/export")) {
            return role.canExport();
        }
        if (path.contains("/mcp/admin/namespaces") || path.contains("/mcp/admin/endpoints")) {
            // 拓扑资源含跨业务方绑定关系，只读也要求拓扑管理权限（超管/管理员）
            return role.canManageTopology();
        }
        if (path.contains("/mcp/admin/service-systems")) {
            return role.canManageSystem();
        }
        if (path.contains("/mcp/admin/tools")
                || path.contains("/mcp/admin/prompts")
                || path.contains("/mcp/admin/resources")) {
            return role.canManageMethod();
        }
        if (path.contains("/mcp/admin/operation-logs")) {
            return role.canView();
        }
        if (path.contains("/mcp/admin/health")
                || path.contains("/mcp/admin/discovery")
                || path.contains("/mcp/admin/servers")
                || path.contains("/mcp/admin/tenants")
                || path.contains("/mcp/admin/overview")
                || path.contains("/mcp/admin/tool-call")) {
            return role.canView();
        }
        // 未知的 GET 接口：只读角色可见（GET 不产生副作用），但仍受租户过滤保护
        return role.canView();
    }

    private static boolean checkWrite(String path, RoleEnum role) {
        // 所有登录用户都可以修改自己的密码
        if (path.contains("/mcp/admin/users/change-password")) {
            return true;
        }
        if (path.contains("/mcp/admin/grant-requests")) {
            // 旧接口已下线：统一走 /mcp/admin/approvals
            return false;
        }
        if (path.contains("/mcp/admin/approvals")) {
            // 提交/审批/驳回/撤回：按登录角色放行，具体动作门槛在 Controller 内按 action 校验
            return role.canManagePermission();
        }
        if (path.contains("/mcp/admin/users")) {
            return role.canManageUser();
        }
        if (path.contains("/mcp/admin/namespaces") || path.contains("/mcp/admin/endpoints")) {
            // 全局访问拓扑：仅平台超管 / 管理员
            return role.canManageTopology();
        }
        if (path.contains("/mcp/admin/tenants")) {
            return role.canManageTenant();
        }
        if (path.contains("/mcp/admin/servers")
                || path.contains("/mcp/admin/tools")
                || path.contains("/mcp/admin/prompts")
                || path.contains("/mcp/admin/resources")) {
            return role.canManageMethod();
        }
        if (path.contains("/mcp/admin/service-systems")) {
            return role.canManageSystem();
        }
        if (path.contains("/mcp/admin/refresh-server-api")) {
            return role.canRefresh();
        }
        if (path.contains("/mcp/admin/health")) {
            return role.canManageMethod();
        }
        // 默认拒绝：未登记的写接口一律 403
        return false;
    }
}
