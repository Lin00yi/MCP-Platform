package com.mcp.transfer.entity;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 审批申请单（通用审批流）。
 *
 * <p>一个申请单对应一个 {@link com.mcp.transfer.enums.ApprovalAction}：
 * 授权 / 撤销授权 / Server 连接变更 / 应用凭证 / 用户提权等。
 * 审批通过后由后端按 {@code payload} 快照执行，执行前会再次校验租户与资源存在性。
 */
@Data
@Accessors(chain = true)
public class ApprovalRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;

    /** 归属业务方（租户隔离） */
    private Integer tenantId;

    /** 操作码，见 ApprovalAction */
    private String action;

    /** PENDING / APPROVED / REJECTED / CANCELLED */
    private String status;

    /** 目标应用（授权类、应用凭证类） */
    private String appId;

    /** 目标 Server */
    private Integer serverId;

    /** 目标 Tool */
    private Integer toolId;

    /** 目标 Prompt */
    private Integer promptId;

    /** 目标 Resource */
    private Integer resourceId;

    /** 目标用户（角色变更） */
    private Long userId;

    /** 执行快照（JSON 明文；落库前由服务端加密） */
    private String payload;

    /** 申请理由 */
    private String reason;

    /** 申请人用户名 */
    private String requester;

    /** 申请人角色 */
    private String requesterRole;

    /** 审批人用户名 */
    private String approver;

    /** 审批人角色 */
    private String approverRole;

    /** 审批意见 */
    private String comment;

    /** 审批通过后生成的一次性凭证密文（APP_CREATE / APP_RESET_SECRET），仅申请人可查看 */
    private String resultSecret;

    private Date createTime;

    private Date updateTime;
}
