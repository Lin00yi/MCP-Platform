package com.mcp.transfer.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 提交审批申请。
 *
 * <p>不同 action 使用不同字段，服务端按 action 校验并生成执行快照：
 * <ul>
 *   <li>授权类：appId + serverId/toolId/promptId/resourceId；批量授权用 serverIds；</li>
 *   <li>撤销类：relId；</li>
 *   <li>Server / Tool / 应用 / 用户类：payload 承载完整请求体。</li>
 * </ul>
 */
@Data
public class ApprovalCreateRequest {

    private String action;

    private String appId;

    private Integer serverId;

    private Integer toolId;

    private Integer promptId;

    private Integer resourceId;

    private List<Integer> serverIds;

    /** 授权关系 ID（撤销类操作） */
    private Long relId;

    /** 目标用户 ID（角色变更） */
    private Long userId;

    /** 执行快照（敏感内容由服务端加密落库） */
    private Map<String, Object> payload;

    private String reason;
}
