package com.mcp.transfer.enums;

import lombok.Getter;

/**
 * 审批风险档位。
 *
 * <ul>
 *   <li>L1：租户内低风险写，开发者可直接执行，只审计；</li>
 *   <li>L2：租户内高影响写（授权、连接、下游凭证），管理员可直接执行，开发者需审批；</li>
 *   <li>L3：平台凭证与提权，超管可直接执行，管理员需审批，开发者不可发起；</li>
 *   <li>L4：平台治理，仅超管可直接执行，不走审批（强审计 + 前端二次确认）。</li>
 * </ul>
 */
@Getter
public enum ApprovalTier {

    L1(1, "租户内低风险"),
    L2(2, "租户内高影响"),
    L3(3, "平台凭证/提权"),
    L4(4, "平台治理");

    private final int level;
    private final String label;

    ApprovalTier(int level, String label) {
        this.level = level;
        this.label = label;
    }
}
