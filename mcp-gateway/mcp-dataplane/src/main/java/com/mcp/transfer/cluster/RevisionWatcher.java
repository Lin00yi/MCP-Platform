package com.mcp.transfer.cluster;

import com.mcp.transfer.shared.mapper.McpServerMapper;
import com.mcp.transfer.shared.mapper.McpToolMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 变更兜底轮询：Redis 事件丢失 / 控制面通知失败时，仍能发现资产变更。
 *
 * <p>做法很轻：每 30s 各查一次 {@code mcp_server} / {@code mcp_tool} 的
 * 「行数 + 最大 update_time」，与上次不同就发一个 {@link ClusterEvent.Type#ALL} 事件，
 * 由 {@link ConfigChangeApplier} 清缓存并刷新路由。
 *
 * <p>默认开启；可用 {@code mcp.cache.revision-poll-enabled=false} 关闭。
 */
@Component
@ConditionalOnProperty(name = "mcp.cache.revision-poll-enabled", havingValue = "true", matchIfMissing = true)
public class RevisionWatcher {

    private static final Logger logger = LoggerFactory.getLogger(RevisionWatcher.class);

    @Autowired
    private ClusterEventBus eventBus;

    @Autowired
    private McpServerMapper mcpServerMapper;

    @Autowired
    private McpToolMapper mcpToolMapper;

    @Value("${mcp.cache.revision-poll-ms:30000}")
    private long pollIntervalMs;

    /** 上一次的版本戳；第一次只记录不触发 */
    private volatile String lastStamp;

    @Scheduled(fixedDelayString = "${mcp.cache.revision-poll-ms:30000}",
            initialDelayString = "${mcp.cache.revision-poll-ms:30000}")
    public void poll() {
        try {
            String stamp = mcpServerMapper.selectRevisionStamp() + "#" + mcpToolMapper.selectRevisionStamp();
            String previous = lastStamp;
            lastStamp = stamp;
            if (previous != null && !previous.equals(stamp)) {
                logger.info("[REVISION] 检测到资产变更（{} → {}），触发全量失效", previous, stamp);
                eventBus.publish(ClusterEvent.of(ClusterEvent.Type.ALL, null));
            }
        } catch (Exception e) {
            logger.warn("[REVISION] 版本戳轮询失败: {}", e.getMessage());
        }
    }
}
