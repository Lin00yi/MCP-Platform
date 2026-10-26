package com.mcp.transfer.shared.mapper;

import com.mcp.transfer.shared.entity.ApprovalRequest;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

public interface ApprovalRequestMapper {

    int insertSelective(ApprovalRequest record);

    ApprovalRequest selectByPrimaryKey(Long id);

    /** 审批时加行锁，避免并发双批 */
    ApprovalRequest selectByPrimaryKeyForUpdate(Long id);

    /** 分页查询：status / requester / tenantIds / actions / excludeRequester 均可选 */
    List<ApprovalRequest> selectWithPage(@Param("status") String status,
                                         @Param("requester") String requester,
                                         @Param("excludeRequester") String excludeRequester,
                                         @Param("tenantIds") List<Integer> tenantIds,
                                         @Param("actions") List<String> actions,
                                         @Param("start") int start,
                                         @Param("limit") int limit);

    long count(@Param("status") String status,
               @Param("requester") String requester,
               @Param("excludeRequester") String excludeRequester,
               @Param("tenantIds") List<Integer> tenantIds,
               @Param("actions") List<String> actions);

    /** 存在相同待审批申请（防重复提交） */
    int countPendingSame(@Param("action") String action,
                         @Param("requester") String requester,
                         @Param("appId") String appId,
                         @Param("serverId") Integer serverId,
                         @Param("toolId") Integer toolId,
                         @Param("promptId") Integer promptId,
                         @Param("resourceId") Integer resourceId,
                         @Param("userId") Long userId);

    /** 条件更新：只有 PENDING 才能流转，返回 0 表示已被处理 */
    int updateStatusIfPending(@Param("id") Long id,
                              @Param("status") String status,
                              @Param("approver") String approver,
                              @Param("approverRole") String approverRole,
                              @Param("comment") String comment,
                              @Param("resultSecret") String resultSecret,
                              @Param("updateTime") Date updateTime);

    /** 申请人被删除时，撤回其待审批申请 */
    int cancelPendingByRequester(@Param("requester") String requester);

    int deleteByServerId(@Param("serverId") Integer serverId);

    int deleteByToolId(@Param("toolId") Integer toolId);

    int deleteByPromptId(@Param("promptId") Integer promptId);

    int deleteByResourceId(@Param("resourceId") Integer resourceId);

    int deleteByUserId(@Param("userId") Long userId);
}
