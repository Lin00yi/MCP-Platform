package com.mcp.transfer.admin.scheduler;

import com.mcp.transfer.shared.mapper.McpRequestLogMapper;
import com.mcp.transfer.shared.mapper.OperationLogMapper;
import com.mcp.transfer.shared.mapper.ToolCallRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 日志保留策略：按天清理超期的请求日志、操作审计日志与工具调用记录，避免 LONGTEXT 无限增长。
 * 可通过 {@code mcp.log.cleanup-enabled=false} 关闭。
 *
 * <p>调用记录（{@code tool_call_record}）是数据量最大的表（每次 tools/call 一行，含出入参 LONGTEXT），
 * 原先没有任何保留策略，会导致磁盘无限增长、后台统计查询逐步劣化；这里补上按天清理。
 */
@Component
@ConditionalOnProperty(name = "mcp.log.cleanup-enabled", havingValue = "true", matchIfMissing = true)
public class LogCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(LogCleanupScheduler.class);

    /** 请求日志保留天数（含请求参数/响应，默认 7 天） */
    @Value("${mcp.log.request-retention-days:7}")
    private int requestRetentionDays;

    /** 审计日志保留天数（默认 180 天） */
    @Value("${mcp.log.operation-retention-days:180}")
    private int operationRetentionDays;

    /** 工具调用记录保留天数（默认 30 天） */
    @Value("${mcp.log.tool-call-retention-days:30}")
    private int toolCallRetentionDays;

    @Autowired
    private McpRequestLogMapper mcpRequestLogMapper;

    @Autowired
    private OperationLogMapper operationLogMapper;

    @Autowired
    private ToolCallRecordMapper toolCallRecordMapper;

    /** 每天 03:15 执行 */
    @Scheduled(cron = "${mcp.log.cleanup-cron:0 15 3 * * ?}")
    public void cleanup() {
        try {
            Date requestBefore = new Date(System.currentTimeMillis() - requestRetentionDays * 24L * 3600_000L);
            int requests = mcpRequestLogMapper.deleteOlderThan(requestBefore);
            Date operationBefore = new Date(System.currentTimeMillis() - operationRetentionDays * 24L * 3600_000L);
            int operations = operationLogMapper.deleteOlderThan(operationBefore);
            int toolCalls = 0;
            if (toolCallRetentionDays > 0) {
                Date toolCallBefore = new Date(System.currentTimeMillis() - toolCallRetentionDays * 24L * 3600_000L);
                toolCalls = toolCallRecordMapper.deleteOlderThan(toolCallBefore);
            }
            log.info("[LOG_CLEANUP] 清理完成：mcp_request_log {} 条（>{}天），operation_log {} 条（>{}天），"
                            + "tool_call_record {} 条（>{}天）",
                    requests, requestRetentionDays, operations, operationRetentionDays,
                    toolCalls, toolCallRetentionDays);
        } catch (Exception e) {
            log.warn("[LOG_CLEANUP] 日志清理失败: {}", e.getMessage(), e);
        }
    }
}
