package com.mcp.transfer.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.RedisPubSubListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.function.Consumer;

/**
 * Redis 连接支撑（懒连接 + 失败冷却 + 优雅降级）。
 *
 * <p>设计约束：
 * <ul>
 *   <li>未开启 {@code mcp.redis.enabled} 时不建立任何连接；</li>
 *   <li>Redis 不可用时调用方必须能降级，因此这里只抛异常不做兜底；</li>
 *   <li>连接建立失败后有冷却期，避免每次请求都重连拖慢响应。</li>
 * </ul>
 */
@Component
public class RedisSupport {

    private static final Logger logger = LoggerFactory.getLogger(RedisSupport.class);

    /** 连接失败后的冷却时间 */
    private static final long RETRY_COOLDOWN_MS = 30_000L;

    private final RedisProperties properties;

    public RedisSupport(RedisProperties properties) {
        this.properties = properties;
    }

    private volatile RedisClient client;
    private volatile StatefulRedisConnection<String, String> connection;
    private volatile StatefulRedisPubSubConnection<String, String> pubSubConnection;
    private volatile long lastFailAt;

    public boolean enabled() {
        return properties.isEnabled();
    }

    /** 同步命令入口；未开启或不健康时抛 {@link RedisUnavailableException} */
    public RedisCommands<String, String> commands() {
        if (!enabled()) {
            throw new RedisUnavailableException("Redis 未开启（mcp.redis.enabled=false）");
        }
        if (System.currentTimeMillis() - lastFailAt < RETRY_COOLDOWN_MS) {
            throw new RedisUnavailableException("Redis 连接处于冷却期");
        }
        try {
            return connection().sync();
        } catch (Exception e) {
            lastFailAt = System.currentTimeMillis();
            closeQuietly();
            throw new RedisUnavailableException("Redis 连接不可用: " + e.getMessage(), e);
        }
    }

    public void publish(String suffixChannel, String message) {
        commands().publish(properties.key(suffixChannel), message);
    }

    /**
     * 订阅频道。订阅成功返回 true；未开启时立即返回 false（调用方据此决定是否本地兜底）。
     */
    public boolean subscribe(String suffixChannel, Consumer<String> handler) {
        if (!enabled()) {
            return false;
        }
        try {
            StatefulRedisPubSubConnection<String, String> conn = pubSubConnection();
            String channel = properties.key(suffixChannel);
            RedisPubSubListener<String, String> listener = new RedisPubSubAdapter<String, String>() {
                @Override
                public void message(String ch, String body) {
                    try {
                        handler.accept(body);
                    } catch (Exception e) {
                        logger.warn("[REDIS] 事件处理失败 channel={}: {}", ch, e.getMessage(), e);
                    }
                }
            };
            conn.addListener(listener);
            conn.sync().subscribe(channel);
            logger.info("[REDIS] 已订阅频道 {}", channel);
            return true;
        } catch (Exception e) {
            lastFailAt = System.currentTimeMillis();
            logger.error("[REDIS] 订阅频道失败，将退化为进程内事件: {}", e.getMessage());
            closeQuietly();
            return false;
        }
    }

    private StatefulRedisConnection<String, String> connection() {
        StatefulRedisConnection<String, String> local = connection;
        if (local != null && local.isOpen()) {
            return local;
        }
        synchronized (this) {
            if (connection != null && connection.isOpen()) {
                return connection;
            }
            RedisClient c = RedisClient.create(RedisURI.create(properties.getUri()));
            StatefulRedisConnection<String, String> conn = c.connect();
            this.client = c;
            this.connection = conn;
            logger.info("[REDIS] 连接已建立 uri={} prefix={}", properties.getUri(), properties.getKeyPrefix());
            return conn;
        }
    }

    private StatefulRedisPubSubConnection<String, String> pubSubConnection() {
        StatefulRedisPubSubConnection<String, String> local = pubSubConnection;
        if (local != null && local.isOpen()) {
            return local;
        }
        synchronized (this) {
            if (pubSubConnection != null && pubSubConnection.isOpen()) {
                return pubSubConnection;
            }
            RedisClient c = client != null ? client : RedisClient.create(RedisURI.create(properties.getUri()));
            this.client = c;
            StatefulRedisPubSubConnection<String, String> conn = c.connectPubSub();
            this.pubSubConnection = conn;
            return conn;
        }
    }

    private void closeQuietly() {
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (Exception ignore) {
            // ignore
        }
        connection = null;
    }

    @PreDestroy
    public void shutdown() {
        try {
            if (pubSubConnection != null) {
                pubSubConnection.close();
            }
        } catch (Exception ignore) {
            // ignore
        }
        closeQuietly();
        try {
            if (client != null) {
                client.shutdown();
            }
        } catch (Exception ignore) {
            // ignore
        }
    }

    /** Redis 不可用（未开启 / 连接失败 / 冷却中） */
    public static class RedisUnavailableException extends RuntimeException {
        public RedisUnavailableException(String message) {
            super(message);
        }

        public RedisUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
