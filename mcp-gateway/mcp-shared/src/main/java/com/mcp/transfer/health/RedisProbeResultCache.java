package com.mcp.transfer.health;

import com.mcp.transfer.redis.RedisProperties;
import com.mcp.transfer.redis.RedisSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis 探测结果缓存（多副本共读）。
 *
 * <p>key：{@code {prefix}health:probe:{serverId}}；TTL 由
 * {@code mcp.health.probe-cache-seconds}（默认 90s）控制，探测间隔 30s，
 * 留出 3 倍余量，单个副本偶尔漏探不会让所有副本同时回退到自行探测。
 */
@Component
@ConditionalOnProperty(name = "mcp.redis.enabled", havingValue = "true")
public class RedisProbeResultCache implements ProbeResultCache {

    private static final Logger logger = LoggerFactory.getLogger(RedisProbeResultCache.class);

    private static final String KEY_PREFIX = "health:probe:";

    private final RedisSupport redis;
    private final RedisProperties properties;

    public RedisProbeResultCache(RedisSupport redis, RedisProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Value("${mcp.health.probe-cache-seconds:90}")
    private long ttlSeconds;

    /** Redis 故障期间的本地兜底 */
    private final Map<Integer, ProbeSnapshot> fallback = new ConcurrentHashMap<>();

    @Override
    public ProbeSnapshot get(Integer serverId) {
        if (serverId == null) {
            return null;
        }
        try {
            String json = redis.commands().get(key(serverId));
            return json == null ? fallback.get(serverId) : ProbeSnapshot.fromJson(json);
        } catch (Exception e) {
            logger.warn("[HEALTH] Redis 读取探测结果失败，使用本地兜底: {}", e.getMessage());
            return fallback.get(serverId);
        }
    }

    @Override
    public void put(Integer serverId, ProbeSnapshot snapshot) {
        if (serverId == null || snapshot == null) {
            return;
        }
        try {
            redis.commands().setex(key(serverId), Math.max(1L, ttlSeconds), snapshot.toJson());
        } catch (Exception e) {
            logger.warn("[HEALTH] Redis 写入探测结果失败，降级为本地: {}", e.getMessage());
            fallback.put(serverId, snapshot);
        }
    }

    @Override
    public boolean shared() {
        return true;
    }

    private String key(Integer serverId) {
        return properties.key(KEY_PREFIX + serverId);
    }
}
