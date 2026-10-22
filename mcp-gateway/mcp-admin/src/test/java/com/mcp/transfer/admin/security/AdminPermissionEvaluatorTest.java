package com.mcp.transfer.admin.security;

import com.mcp.transfer.enums.RoleEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理端权限矩阵回归：重点验证「默认拒绝」与敏感 GET 收紧。
 */
class AdminPermissionEvaluatorTest {

    @Test
    void writeEndpointsRequireExplicitPermission() {
        // 旧授权申请接口已下线，统一走 /approvals
        assertFalse(AdminPermissionEvaluator.check("POST", "/mcp/admin/grant-requests/1/approve", RoleEnum.SUPER_ADMIN));

        // 审批接口：开发者可提交/查看自己的申请，但不能审批（Controller 按 action 再校验）
        assertTrue(AdminPermissionEvaluator.check("POST", "/mcp/admin/approvals", RoleEnum.DEVELOPER));
        assertTrue(AdminPermissionEvaluator.check("POST", "/mcp/admin/approvals/1/approve", RoleEnum.ADMIN));
        assertFalse(AdminPermissionEvaluator.check("POST", "/mcp/admin/approvals/1/approve", RoleEnum.USER));
        assertFalse(AdminPermissionEvaluator.check("POST", "/mcp/admin/approvals/1/approve", RoleEnum.OPERATOR));

        // 未登记的写接口默认拒绝
        assertFalse(AdminPermissionEvaluator.check("POST", "/mcp/admin/unknown-write", RoleEnum.SUPER_ADMIN));
    }

    @Test
    void approvalReadsAreRestricted() {
        assertTrue(AdminPermissionEvaluator.check("GET", "/mcp/admin/approvals/policy", RoleEnum.USER));
        assertFalse(AdminPermissionEvaluator.check("GET", "/mcp/admin/approvals", RoleEnum.USER));
        assertFalse(AdminPermissionEvaluator.check("GET", "/mcp/admin/approvals/1", RoleEnum.OPERATOR));
        assertTrue(AdminPermissionEvaluator.check("GET", "/mcp/admin/approvals", RoleEnum.DEVELOPER));
        assertTrue(AdminPermissionEvaluator.check("GET", "/mcp/admin/approvals/1/secret", RoleEnum.DEVELOPER));
    }

    @Test
    void sensitiveReadsAreRestricted() {
        assertFalse(AdminPermissionEvaluator.check("GET", "/mcp/admin/tools", RoleEnum.USER));
        assertFalse(AdminPermissionEvaluator.check("GET", "/mcp/admin/service-systems", RoleEnum.USER));

        assertTrue(AdminPermissionEvaluator.check("GET", "/mcp/admin/tools", RoleEnum.DEVELOPER));
        assertTrue(AdminPermissionEvaluator.check("GET", "/mcp/admin/service-systems", RoleEnum.DEVELOPER));
    }

    @Test
    void readOnlyEndpointsRemainVisible() {
        assertTrue(AdminPermissionEvaluator.check("GET", "/mcp/admin/overview", RoleEnum.USER));
        assertTrue(AdminPermissionEvaluator.check("GET", "/mcp/admin/servers", RoleEnum.USER));
        assertTrue(AdminPermissionEvaluator.check("GET", "/mcp/admin/discovery/servers", RoleEnum.USER));
        assertTrue(AdminPermissionEvaluator.check("GET", "/mcp/admin/health/servers", RoleEnum.USER));
    }

    @Test
    void changePasswordAllowedForAllLoggedInUsers() {
        assertTrue(AdminPermissionEvaluator.check("POST", "/mcp/admin/users/change-password", RoleEnum.USER));
        assertFalse(AdminPermissionEvaluator.check("POST", "/mcp/admin/users", RoleEnum.USER));
        assertTrue(AdminPermissionEvaluator.check("POST", "/mcp/admin/users", RoleEnum.ADMIN));
    }

}
