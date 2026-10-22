package com.mcp.transfer.gateway.session;

import com.mcp.transfer.redis.RedisProperties;
import com.mcp.transfer.redis.RedisSupport;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Redis 上游会话映射（多副本）。
 *
 * <p>key：{@code {prefix}upstream:{gatewaySessionKey}}，field：上游名（单上游固定 {@code __single__}），
 * value：下游 {@code Mcp-Session-Id}；整个 Hash 的 TTL 随最近一次写入刷新。
 */
@Component
@ConditionalOnProperty(name = "mcp.redis.enabled", havingValue = "true")
public class RedisUpstreamSessionStore implements UpstreamSessionStore {

    private static final Logger logger = LoggerFactory.getLogger(RedisUpstreamSessionStore.class);

    private static final String KEY_PREFIX = "upstream:";

    private final RedisSupport redis;
    private final RedisProperties properties;

    public RedisUpstreamSessionStore(RedisSupport redis, RedisProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** Redis 故障期间的本地兜底 */
    private final Map<String, Map<String, String>> fallback = new HashMap<>();

    @Override
    public String get(String gatewaySessionKey, String upstreamKey) {
        if (gatewaySessionKey == null) {
            return null;
        }
        String field = field(upstreamKey);
        try {
            return redis.commands().hget(key(gatewaySessionKey), field);
        } catch (Exception e) {
            logger.warn("[UPSTREAM] Redis 读取失败，使用本地兜底: {}", e.getMessage());
            return localGet(gatewaySessionKey, field);
        }
    }

    @Override
    public void put(String gatewaySessionKey, String upstreamKey, String upstreamSessionId, long ttlMillis) {
        if (gatewaySessionKey == null || upstreamSessionId == null) {
            return;
        }
        String field = field(upstreamKey);
        try {
            RedisCommands<String, String> cmd = redis.commands();
            String key = key(gatewaySessionKey);
            cmd.hset(key, field, upstreamSessionId);
            cmd.pexpire(key, Math.max(1000L, ttlMillis));
        } catch (Exception e) {
            logger.warn("[UPSTREAM] Redis 写入失败，使用本地兜底: {}", e.getMessage());
            localPut(gatewaySessionKey, field, upstreamSessionId);
        }
    }

    @Override
    public void remove(String gatewaySessionKey) {
        if (gatewaySessionKey == null) {
            return;
        }
        fallback.remove(gatewaySessionKey);
        try {
            redis.commands().del(key(gatewaySessionKey));
        } catch (Exception e) {
            logger.warn("[UPSTREAM] Redis 删除失败: {}", e.getMessage());
        }
    }

    @Override
    public int size() {
        return -1;
    }

    private String key(String gatewaySessionKey) {
        return properties.key(KEY_PREFIX + gatewaySessionKey);
    }

    private String field(String upstreamKey) {
        return upstreamKey == null || upstreamKey.isEmpty() ? "__single__" : upstreamKey;
    }

    private synchronized String localGet(String gatewaySessionKey, String field) {
        Map<String, String> map = fallback.get(gatewaySessionKey);
        return map == null ? null : map.get(field);
    }

    private synchronized void localPut(String gatewaySessionKey, String field, String upstreamSessionId) {
        if (fallback.size() > 10_000) {
            fallback.clear();
            logger.warn("[UPSTREAM] 本地兜底超过 10000 条，已清空");
        }
        fallback.computeIfAbsent(gatewaySessionKey, k -> new HashMap<>()).put(field, upstreamSessionId);
    }
}
