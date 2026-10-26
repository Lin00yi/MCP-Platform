package com.mcp.transfer.shared.mapper;

import com.mcp.transfer.shared.entity.ToolCallRecord;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;

public interface ToolCallRecordMapper {
    int insert(ToolCallRecord record);

    int insertSelective(ToolCallRecord record);

    /**
     * 批量写入调用记录（数据面异步 flush 用）。
     * 调用方需保证单批不超过 200 条，避免单条 SQL 过大。
     */
    int insertBatch(@Param("records") List<ToolCallRecord> records);

    /** 删除 create_time 早于 before 的调用记录（日志保留策略用），返回删除行数 */
    int deleteOlderThan(@Param("before") Date before);

    /** 分页查询工具调用记录（管理后台用，success 可选过滤：1成功 0失败，tenantIds 可选过滤） */
    List<ToolCallRecord> selectAllWithPage(@Param("start") int start, @Param("limit") int limit, @Param("success") Integer success, @Param("tenantIds") List<Integer> tenantIds);

    /** 工具调用记录总数（可选按 success / tenantIds 过滤） */
    long countAll(@Param("success") Integer success, @Param("tenantIds") List<Integer> tenantIds);

    /** 自某时间点以来的工具调用数（tenantIds 可选过滤） */
    long countSince(@Param("start") Date start, @Param("tenantIds") List<Integer> tenantIds);

    /** 分页查询（含时间范围 + 成功状态 + 租户过滤） */
    List<ToolCallRecord> selectWithFilter(@Param("start") int start, @Param("limit") int limit,
                                          @Param("begin") Date begin, @Param("end") Date end,
                                          @Param("success") Integer success,
                                          @Param("serverId") Integer serverId,
                                          @Param("appId") String appId,
                                          @Param("userId") String userId,
                                          @Param("tenantIds") List<Integer> tenantIds);

    long countWithFilter(@Param("begin") Date begin, @Param("end") Date end,
                         @Param("success") Integer success,
                         @Param("serverId") Integer serverId,
                         @Param("appId") String appId,
                         @Param("userId") String userId,
                         @Param("tenantIds") List<Integer> tenantIds);

    /** 工具调用统计（total/today/successCount/failCount/avgDuration，tenantIds 可选过滤） */
    Map<String, Object> stats(@Param("todayStart") Date todayStart, @Param("tenantIds") List<Integer> tenantIds);

    /** 近 N 天按天统计趋势（tenantIds 可选过滤） */
    List<Map<String, Object>> trendSince(@Param("start") Date start, @Param("tenantIds") List<Integer> tenantIds);

    /** 按方法 code 统计调用分布（tenantIds 可选过滤） */
    List<Map<String, Object>> countByMethod(@Param("tenantIds") List<Integer> tenantIds);

    /** 按调用类型统计分布（tenantIds 可选过滤） */
    List<Map<String, Object>> countByCallType(@Param("tenantIds") List<Integer> tenantIds);

    /** 按 server 聚合某时间之后的调用统计（健康检测用） */
    List<Map<String, Object>> selectServerStatsSince(@Param("since") Date since);

    /**
     * 某 server 在时间窗口内调用次数最多的工具（META 热工具推荐用）。
     * 只统计成功调用，避免把报错重试的工具误判为高频。
     */
    List<Map<String, Object>> topToolCodes(@Param("serverId") Integer serverId,
                                           @Param("since") Date since,
                                           @Param("limit") int limit);
}
