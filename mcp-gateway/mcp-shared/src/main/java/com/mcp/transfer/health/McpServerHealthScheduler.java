package com.mcp.transfer.health;

import com.mcp.transfer.entity.McpServer;
import com.mcp.transfer.mapper.McpServerMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.CollectionUtils;

import javax.annotation.PostConstruct;
import java.util.List;

/**
 * 定时探活：默认每 30s 探测所有启用中的 Server（结果进缓存，供接口/控制台读取）。
 *
 * <p>多副本部署时由 {@link ProbeLeader} 选主，只有 leader 真正探测；
 * 其它副本跳过定时任务，直接读 leader 写进共享缓存的结果。
 *
 * <p>可通过 mcp.health.enabled=false 关闭。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "mcp.health.enabled", havingValue = "true", matchIfMissing = true)
public class McpServerHealthScheduler {

    private static final Logger logger = LoggerFactory.getLogger(McpServerHealthScheduler.class);

    @Autowired
    private McpServerMapper mcpServerMapper;

    @Autowired
    private McpServerHealthService healthService;

    @Autowired
    private ProbeLeader probeLeader;

    @org.springframework.beans.factory.annotation.Value("${mcp.health.probe-interval-ms:30000}")
    private long probeIntervalMs;

    @PostConstruct
    public void init() {
        logger.info("[HEALTH] 定时探活已启用，间隔 {}ms（可通过 mcp.health.enabled=false 关闭）", probeIntervalMs);
    }

    @Scheduled(fixedDelayString = "${mcp.health.probe-interval-ms:30000}", initialDelayString = "${mcp.health.initial-delay-ms:15000}")
    public void probeAll() {
        if (!probeLeader.isLeader()) {
            logger.debug("[HEALTH] 本副本不是探活 leader，跳过本轮探测");
            return;
        }
        try {
            List<McpServer> servers = mcpServerMapper.getAllByStatus(1);
            if (CollectionUtils.isEmpty(servers)) {
                return;
            }
            // 顺序探测，单个超时受 mcp.health.timeout-ms 控制；fixedDelay 保证不重叠
            healthService.healthOfAll(servers);
            logger.debug("[HEALTH] 定时探活完成，共 {} 个 Server", servers.size());
        } catch (Exception e) {
            logger.warn("[HEALTH] 定时探活失败: {}", e.getMessage());
        }
    }
}
