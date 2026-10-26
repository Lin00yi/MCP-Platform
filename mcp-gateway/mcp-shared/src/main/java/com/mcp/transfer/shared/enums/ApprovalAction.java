package com.mcp.transfer.shared.enums;

import lombok.Getter;

/**
 * 纳入审批矩阵的操作。
 *
 * <p>未在此枚举中的写操作视为 L1（开发者及以上直接执行 + 审计）。
 * 每个 action 声明自己的风险档位，审批策略统一由
 * {@link com.mcp.transfer.admin.approval.ApprovalPolicy} 推导：
 * 直接执行所需角色、可发起角色、审批人角色。
 */
@Getter
public enum ApprovalAction {

    SERVER_CREATE("SERVER_CREATE", "创建 MCP Server", ApprovalTier.L2),
    SERVER_UPDATE_CONNECTION("SERVER_UPDATE_CONNECTION", "变更 Server 连接配置", ApprovalTier.L2),
    SERVER_DELETE("SERVER_DELETE", "删除 MCP Server", ApprovalTier.L2),
    TOOL_CREDENTIAL_UPDATE("TOOL_CREDENTIAL_UPDATE", "变更 Tool 下游凭证", ApprovalTier.L2),

    USER_ROLE_UPDATE("USER_ROLE_UPDATE", "变更用户角色", ApprovalTier.L3),

    TENANT_CREATE("TENANT_CREATE", "创建业务方", ApprovalTier.L4),
    TENANT_UPDATE("TENANT_UPDATE", "更新业务方", ApprovalTier.L4),
    TENANT_DELETE("TENANT_DELETE", "删除业务方", ApprovalTier.L4);

    private final String code;
    private final String label;
    private final ApprovalTier tier;

    ApprovalAction(String code, String label, ApprovalTier tier) {
        this.code = code;
        this.label = label;
        this.tier = tier;
    }

    public static ApprovalAction getByCode(String code) {
        if (code == null) {
            return null;
        }
        for (ApprovalAction a : values()) {
            if (a.code.equals(code)) {
                return a;
            }
        }
        return null;
    }
}
