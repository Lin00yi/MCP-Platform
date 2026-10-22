package com.mcp.transfer.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内探测结果缓存（默认，单副本）。
 */
@Component
@ConditionalOnProperty(name = "mcp.redis.enabled", havingValue = "false", matchIfMissing = true)
public class MemoryProbeResultCache implements ProbeResultCache {

    private static final Logger logger = LoggerFactory.getLogger(MemoryProbeResultCache.class);

    private final Map<Integer, ProbeSnapshot> cache = new ConcurrentHashMap<>();

    /** 与探测间隔配合：略大于间隔，避免刚好过期导致重复探测 */
    @Value("${mcp.health.probe-cache-seconds:90}")
    private long ttlSeconds;

    @Override
    public ProbeSnapshot get(Integer serverId) {
        if (serverId == null) {
            return null;
        }
        ProbeSnapshot snapshot = cache.get(serverId);
        if (snapshot == null) {
            return null;
        }
        if (System.currentTimeMillis() - snapshot.checkedAt > ttlSeconds * 1000L) {
            cache.remove(serverId);
            return null;
        }
        return snapshot;
    }

    @Override
    public void put(Integer serverId, ProbeSnapshot snapshot) {
        if (serverId != null && snapshot != null) {
            cache.put(serverId, snapshot);
        }
    }

    @Override
    public boolean shared() {
        return false;
    }
}
