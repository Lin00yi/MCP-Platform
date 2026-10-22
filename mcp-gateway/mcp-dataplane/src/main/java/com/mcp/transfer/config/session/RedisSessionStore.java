package com.mcp.transfer.config.session;

import com.alibaba.fastjson.JSON;
import com.mcp.transfer.redis.RedisProperties;
import com.mcp.transfer.redis.RedisSupport;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis 会话存储（多副本共享）。
 *
 * <p>key：{@code {prefix}session:{sessionId}}，value：{@link SessionInfo} 的 JSON，TTL 与网关会话一致。
 *
 * <p>降级策略：Redis 不可用时退化为进程内 Map（只影响当前副本），并打告警；
 * 不因为缓存不可用而让 MCP 请求 500。
 */
@Component
@ConditionalOnProperty(name = "mcp.redis.enabled", havingValue = "true")
public class RedisSessionStore implements SessionStore {

    private static final Logger logger = LoggerFactory.getLogger(RedisSessionStore.class);

    private static final String KEY_PREFIX = "session:";

    private final RedisSupport redis;
    private final RedisProperties properties;

    public RedisSessionStore(RedisSupport redis, RedisProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** 降级用的本地缓存（Redis 故障期间） */
    private final Map<String, SessionInfo> fallback = new ConcurrentHashMap<>();

    @Value("${mcp.session.max-count:20000}")
    private int maxCount;

    @Override
    public void save(String sessionId, SessionInfo info, long ttlMillis) {
        if (sessionId == null || info == null) {
            return;
        }
        long ttlSeconds = Math.max(1L, ttlMillis / 1000L);
        try {
            RedisCommands<String, String> cmd = redis.commands();
            cmd.setex(key(sessionId), ttlSeconds, JSON.toJSONString(info));
        } catch (Exception e) {
            logger.warn("[SESSION] Redis 写入失败，降级为本地会话: {}", e.getMessage());
            putLocal(sessionId, info);
        }
    }

    @Override
    public SessionInfo find(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return null;
        }
        try {
            String json = redis.commands().get(key(sessionId));
            if (json == null) {
                return fallback.get(sessionId);
            }
            return JSON.parseObject(json, SessionInfo.class);
        } catch (Exception e) {
            logger.warn("[SESSION] Redis 读取失败，降级为本地会话: {}", e.getMessage());
            return fallback.get(sessionId);
        }
    }

    @Override
    public void remove(String sessionId) {
        if (sessionId == null) {
            return;
        }
        fallback.remove(sessionId);
        try {
            redis.commands().del(key(sessionId));
        } catch (Exception e) {
            logger.warn("[SESSION] Redis 删除失败: {}", e.getMessage());
        }
    }

    @Override
    public int size() {
        // 跨副本会话总量需要 SCAN，代价高且无消费方；返回 -1 表示"未知/共享"
        return -1;
    }

    @Override
    public boolean shared() {
        return true;
    }

    /** Redis 故障期间的本地兜底容量控制 */
    private void putLocal(String sessionId, SessionInfo info) {
        if (maxCount > 0 && fallback.size() >= maxCount) {
            fallback.clear();
            logger.warn("[SESSION] 本地降级会话达到上限 {}，已清空", maxCount);
        }
        fallback.put(sessionId, info);
    }

    private String key(String sessionId) {
        return properties.key(KEY_PREFIX + sessionId);
    }
}
