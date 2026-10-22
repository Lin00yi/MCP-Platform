package com.mcp.transfer.admin.approval;

import com.mcp.transfer.enums.ApprovalAction;
import com.mcp.transfer.enums.RoleEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批矩阵回归：直接执行角色、发起门槛、审批人门槛。
 */
class ApprovalPolicyTest {

    @Test
    void developerNeedsApprovalForTenantHighRiskActions() {
        ApprovalAction action = ApprovalAction.SERVER_CREATE;
        assertFalse(ApprovalPolicy.canDirect(action, RoleEnum.DEVELOPER));
        assertTrue(ApprovalPolicy.canInitiate(action, RoleEnum.DEVELOPER));
        assertTrue(ApprovalPolicy.requiresApproval(action, RoleEnum.DEVELOPER));
        assertEquals(RoleEnum.ADMIN, ApprovalPolicy.requiredApproverRole(action));
        assertFalse(ApprovalPolicy.canApprove(action, RoleEnum.DEVELOPER));
        assertTrue(ApprovalPolicy.canApprove(action, RoleEnum.ADMIN));
    }

    @Test
    void adminExecutesTenantHighRiskActionsDirectly() {
        ApprovalAction action = ApprovalAction.SERVER_DELETE;
        assertTrue(ApprovalPolicy.canDirect(action, RoleEnum.ADMIN));
        assertFalse(ApprovalPolicy.requiresApproval(action, RoleEnum.ADMIN));
        assertTrue(ApprovalPolicy.canDirect(action, RoleEnum.SUPER_ADMIN));
    }

    @Test
    void adminNeedsSuperAdminForPlatformCredentials() {
        ApprovalAction action = ApprovalAction.USER_ROLE_UPDATE;
        assertFalse(ApprovalPolicy.canDirect(action, RoleEnum.ADMIN));
        assertTrue(ApprovalPolicy.canInitiate(action, RoleEnum.ADMIN));
        assertEquals(RoleEnum.SUPER_ADMIN, ApprovalPolicy.requiredApproverRole(action));
        assertFalse(ApprovalPolicy.canApprove(action, RoleEnum.ADMIN));
        assertTrue(ApprovalPolicy.canApprove(action, RoleEnum.SUPER_ADMIN));
        // 开发者连申请都发不了
        assertFalse(ApprovalPolicy.canInitiate(action, RoleEnum.DEVELOPER));
    }

    @Test
    void platformGovernanceIsDirectOnly() {
        ApprovalAction action = ApprovalAction.TENANT_DELETE;
        assertTrue(ApprovalPolicy.canDirect(action, RoleEnum.SUPER_ADMIN));
        assertFalse(ApprovalPolicy.canDirect(action, RoleEnum.ADMIN));
        assertFalse(ApprovalPolicy.canInitiate(action, RoleEnum.ADMIN));
        assertFalse(ApprovalPolicy.canInitiate(action, RoleEnum.DEVELOPER));
    }

    @Test
    void operatorAndUserCannotInitiate() {
        assertFalse(ApprovalPolicy.canInitiate(ApprovalAction.TOOL_CREDENTIAL_UPDATE, RoleEnum.OPERATOR));
        assertFalse(ApprovalPolicy.canInitiate(ApprovalAction.TOOL_CREDENTIAL_UPDATE, RoleEnum.USER));
        assertFalse(ApprovalPolicy.canDirect(ApprovalAction.TOOL_CREDENTIAL_UPDATE, RoleEnum.OPERATOR));
    }

    @Test
    void approvalHintNamesRequiredApprover() {
        assertTrue(ApprovalPolicy.approvalHint(ApprovalAction.TOOL_CREDENTIAL_UPDATE).contains("管理员"));
        assertTrue(ApprovalPolicy.approvalHint(ApprovalAction.USER_ROLE_UPDATE).contains("平台超管"));
    }
}
