package com.mcp.transfer.dto;

import lombok.Data;

/**
 * 审批决定（通过 / 驳回 / 撤回）的附加意见。
 */
@Data
public class ApprovalDecisionRequest {

    private String comment;
}
