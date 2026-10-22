package com.mcp.transfer.admin.approval;

import com.mcp.transfer.enums.ApprovalAction;
import com.mcp.transfer.enums.ApprovalTier;
import com.mcp.transfer.enums.RoleEnum;

/**
 * 审批矩阵唯一事实来源（前后端都以此为准）。
 *
 * <p>核心规则：
 * <ol>
 *   <li>每个 action 有「直接执行所需角色」= 该档位对应的角色；</li>
 *   <li>角色等级高于等于该角色 → 直接执行，不产生审批单；</li>
 *   <li>角色等级低于该角色但达到发起门槛 → 必须提交审批，审批人角色 = 直接执行角色；</li>
 *   <li>审批人必须严格高于申请人，且不能是申请人本人；</li>
 *   <li>L4 只能超管直接执行，不接受审批申请。</li>
 * </ol>
 */
public final class ApprovalPolicy {

    private ApprovalPolicy() {
    }

    /** 角色等级：超管 4 > 管理员 3 > 开发者 2 > 运维 1 > 用户 0 */
    public static int level(RoleEnum role) {
        if (role == null) {
            return -1;
        }
        switch (role) {
            case SUPER_ADMIN:
                return 4;
            case ADMIN:
                return 3;
            case DEVELOPER:
                return 2;
            case OPERATOR:
                return 1;
            case USER:
            default:
                return 0;
        }
    }

    /** 该操作直接执行所需的最低角色 */
    public static RoleEnum directRole(ApprovalAction action) {
        ApprovalTier tier = action == null ? ApprovalTier.L1 : action.getTier();
        switch (tier) {
            case L1:
                return RoleEnum.DEVELOPER;
            case L2:
                return RoleEnum.ADMIN;
            case L3:
            case L4:
            default:
                return RoleEnum.SUPER_ADMIN;
        }
    }

    /** 是否允许直接执行（不产生审批单） */
    public static boolean canDirect(ApprovalAction action, RoleEnum role) {
        return action != null && level(role) >= level(directRole(action));
    }

    /** 是否允许发起该操作的审批申请 */
    public static boolean canInitiate(ApprovalAction action, RoleEnum role) {
        if (action == null || role == null) {
            return false;
        }
        if (action.getTier() == ApprovalTier.L4) {
            // 平台治理只允许超管直接执行，不接受申请
            return false;
        }
        if (action.getTier() == ApprovalTier.L3) {
            // 平台凭证/提权：管理员发起，超管审批；开发者不可发起
            return level(role) >= level(RoleEnum.ADMIN);
        }
        return level(role) >= level(RoleEnum.DEVELOPER);
    }

    /** 该角色执行该操作是否必须走审批 */
    public static boolean requiresApproval(ApprovalAction action, RoleEnum role) {
        return canInitiate(action, role) && !canDirect(action, role);
    }

    /** 审批人所需的最低角色 */
    public static RoleEnum requiredApproverRole(ApprovalAction action) {
        return directRole(action);
    }

    /** 审批人角色是否满足该操作 */
    public static boolean canApprove(ApprovalAction action, RoleEnum approverRole) {
        return action != null && level(approverRole) >= level(requiredApproverRole(action));
    }

    /**
     * 生产审批拒绝提示（含审批人角色），供后端直写接口在拦截时返回。
     */
    public static String approvalHint(ApprovalAction action) {
        RoleEnum approver = requiredApproverRole(action);
        return "该操作需审批：请到「审批」页提交申请，由" + approver.getName() + "及以上审批";
    }
}
