package com.mcp.transfer.gateway.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内上游会话映射（默认实现）。
 */
@Component
@ConditionalOnProperty(name = "mcp.redis.enabled", havingValue = "false", matchIfMissing = true)
public class MemoryUpstreamSessionStore implements UpstreamSessionStore {

    private static final Logger logger = LoggerFactory.getLogger(MemoryUpstreamSessionStore.class);

    /** 容量上限，避免长期运行内存泄漏 */
    private static final int MAX_ENTRIES = 10_000;

    private static final class Ref {
        final String upstreamSessionId;
        final long expiresAt;

        Ref(String upstreamSessionId, long expiresAt) {
            this.upstreamSessionId = upstreamSessionId;
            this.expiresAt = expiresAt;
        }
    }

    private final Map<String, Map<String, Ref>> sessions = new ConcurrentHashMap<>();

    @Override
    public String get(String gatewaySessionKey, String upstreamKey) {
        if (gatewaySessionKey == null) {
            return null;
        }
        Map<String, Ref> byUpstream = sessions.get(gatewaySessionKey);
        if (byUpstream == null) {
            return null;
        }
        Ref ref = byUpstream.get(upstreamKey == null ? "" : upstreamKey);
        if (ref == null) {
            return null;
        }
        if (ref.expiresAt <= System.currentTimeMillis()) {
            byUpstream.remove(upstreamKey == null ? "" : upstreamKey);
            return null;
        }
        return ref.upstreamSessionId;
    }

    @Override
    public void put(String gatewaySessionKey, String upstreamKey, String upstreamSessionId, long ttlMillis) {
        if (gatewaySessionKey == null || upstreamSessionId == null) {
            return;
        }
        if (sessions.size() > MAX_ENTRIES) {
            sessions.clear();
            logger.warn("[UPSTREAM] 上游会话映射超过 {} 条，已清空", MAX_ENTRIES);
        }
        sessions.computeIfAbsent(gatewaySessionKey, k -> new ConcurrentHashMap<>())
                .put(upstreamKey == null ? "" : upstreamKey,
                        new Ref(upstreamSessionId, System.currentTimeMillis() + ttlMillis));
    }

    @Override
    public void remove(String gatewaySessionKey) {
        if (gatewaySessionKey != null) {
            sessions.remove(gatewaySessionKey);
        }
    }

    @Override
    public int size() {
        return sessions.size();
    }
}
