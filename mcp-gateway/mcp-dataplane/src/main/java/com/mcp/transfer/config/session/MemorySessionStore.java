package com.mcp.transfer.config.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 进程内会话存储（默认实现）。
 *
 * <p>保留原来的容量上限与定时清理：匿名 initialize 不能把内存刷爆。
 */
@Component
@ConditionalOnProperty(name = "mcp.redis.enabled", havingValue = "false", matchIfMissing = true)
public class MemorySessionStore implements SessionStore {

    private static final Logger logger = LoggerFactory.getLogger(MemorySessionStore.class);

    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();

    @Value("${mcp.session.max-count:20000}")
    private int maxCount;

    private final ScheduledExecutorService scheduler;

    public MemorySessionStore() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mcp-session-cleaner");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(this::cleanExpired, 5, 5, TimeUnit.MINUTES);
    }

    @Override
    public void save(String sessionId, SessionInfo info, long ttlMillis) {
        if (maxCount > 0 && sessions.size() >= maxCount) {
            cleanExpired();
            if (sessions.size() >= maxCount) {
                evictOldest(sessions.size() - maxCount + 1);
            }
        }
        sessions.put(sessionId, info);
    }

    @Override
    public SessionInfo find(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return null;
        }
        SessionInfo info = sessions.get(sessionId);
        if (info == null) {
            return null;
        }
        if (System.currentTimeMillis() > info.getExpiresAt()) {
            sessions.remove(sessionId);
            return null;
        }
        return info;
    }

    @Override
    public void remove(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    @Override
    public int size() {
        return sessions.size();
    }

    @Override
    public boolean shared() {
        return false;
    }

    private void cleanExpired() {
        long now = System.currentTimeMillis();
        int cleaned = 0;
        for (Map.Entry<String, SessionInfo> entry : sessions.entrySet()) {
            if (entry.getValue().getExpiresAt() < now) {
                sessions.remove(entry.getKey());
                cleaned++;
            }
        }
        if (cleaned > 0) {
            logger.debug("MCP Session 缓存清理完成，共清理 {} 个过期 Session", cleaned);
        }
    }

    private void evictOldest(int count) {
        if (count <= 0) {
            return;
        }
        sessions.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().getExpiresAt()))
                .limit(count)
                .map(Map.Entry::getKey)
                .forEach(sessions::remove);
        logger.warn("MCP Session 缓存达到上限 {}，已淘汰 {} 个最早过期的 Session", maxCount, count);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
