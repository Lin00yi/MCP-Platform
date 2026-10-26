package com.mcp.transfer.exposure;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 超长工具结果的暂存区（call_id → 全文）。
 *
 * <p>仅用于 read_result_chunk 的分段读取，属于会话期临时数据：
 * <ul>
 *   <li>按 appId 隔离，防止跨应用猜测 call_id 读到别人的数据；</li>
 *   <li>LRU + TTL；</li>
 *   <li>{@code mcp.tools.chunk-store=memory}（默认）时进程内；
 *       {@code =redis} 时同时写 Redis，支持多副本跨节点读取；Redis 不可用自动回退内存。</li>
 * </ul>
 *
 * <p>Redis 只存文本与元数据，key 前缀 {@code mcp:chunk:}，TTL 由
 * {@code mcp.tools.chunk-ttl-seconds} 控制。
 */
@Component
public class ToolResultStore {

    private static final Logger logger = LoggerFactory.getLogger(ToolResultStore.class);

    private static final String KEY_PREFIX = "mcp:chunk:";

    /** Redis 失败后的冷却时间，避免每次请求都重连 */
    private static final long REDIS_RETRY_MS = 30_000L;

    @Autowired
    private ToolExposureProperties properties;

    @Value("${mcp.tools.chunk-store:memory}")
    private String chunkStore = "memory";

    @Value("${mcp.tools.redis-uri:${mcp.redis.uri:redis://127.0.0.1:6379/0}}")
    private String redisUri = "redis://127.0.0.1:6379/0";

    private final LinkedHashMap<String, Entry> cache = new LinkedHashMap<String, Entry>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > Math.max(1, properties.getChunkCacheSize());
        }
    };

    private volatile RedisClient redisClient;
    private volatile StatefulRedisConnection<String, String> redisConnection;
    private volatile long lastRedisFailureAt;

    /** 暂存全文，返回 call_id。 */
    public String put(String appId, String scope, String text, int totalChars, boolean truncated) {
        String callId = UUID.randomUUID().toString().replace("-", "");
        Entry entry = new Entry(appId, scope, text, totalChars, truncated,
                System.currentTimeMillis());
        synchronized (this) {
            cache.put(callId, entry);
        }
        RedisCommands<String, String> commands = commands();
        if (commands != null) {
            try {
                commands.setex(KEY_PREFIX + callId,
                        Math.max(1, properties.getChunkTtlSeconds()), toJson(entry));
                markRedisSuccess();
            } catch (Exception e) {
                markRedisFailure(e);
            }
        }
        return callId;
    }

    /** 读取；不存在、已过期或 appId 不匹配返回 null。 */
    public Entry get(String appId, String callId) {
        if (callId == null) {
            return null;
        }
        RedisCommands<String, String> commands = commands();
        if (commands != null) {
            try {
                String json = commands.get(KEY_PREFIX + callId);
                if (json != null) {
                    Entry entry = fromJson(json);
                    if (entry != null) {
                        if (entry.appId != null && !entry.appId.equals(appId)) {
                            return null;
                        }
                        markRedisSuccess();
                        return entry;
                    }
                }
            } catch (Exception e) {
                markRedisFailure(e);
            }
        }
        synchronized (this) {
            return localGet(appId, callId);
        }
    }

    /** 测试/排障用：清理进程内缓存（Redis key 依赖 TTL 自然过期）。 */
    public synchronized void clear() {
        cache.clear();
    }

    @PreDestroy
    public void shutdown() {
        closeRedis();
    }

    private Entry localGet(String appId, String callId) {
        Entry entry = cache.get(callId);
        if (entry == null) {
            return null;
        }
        long ttl = Math.max(0, properties.getChunkTtlSeconds()) * 1000L;
        if (ttl > 0 && System.currentTimeMillis() - entry.createdAt > ttl) {
            cache.remove(callId);
            return null;
        }
        if (entry.appId != null && !entry.appId.equals(appId)) {
            return null;
        }
        return entry;
    }

    // ==================== Redis ====================

    private boolean redisEnabled() {
        return "redis".equalsIgnoreCase(chunkStore);
    }

    private RedisCommands<String, String> commands() {
        if (!redisEnabled()) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now - lastRedisFailureAt < REDIS_RETRY_MS) {
            return null;
        }
        try {
            StatefulRedisConnection<String, String> connection = redisConnection;
            if (connection == null || !connection.isOpen()) {
                synchronized (this) {
                    connection = redisConnection;
                    if (connection == null || !connection.isOpen()) {
                        closeRedis();
                        RedisClient client = RedisClient.create(redisUri);
                        this.redisClient = client;
                        this.redisConnection = client.connect();
                        connection = this.redisConnection;
                    }
                }
            }
            return connection.sync();
        } catch (Exception e) {
            markRedisFailure(e);
            return null;
        }
    }

    private void markRedisSuccess() {
        lastRedisFailureAt = 0;
    }

    private void markRedisFailure(Exception e) {
        lastRedisFailureAt = System.currentTimeMillis();
        logger.warn("[CHUNK] Redis 不可用，回退进程内缓存: {}", e.getMessage());
        closeRedis();
    }

    private void closeRedis() {
        StatefulRedisConnection<String, String> connection = redisConnection;
        redisConnection = null;
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignore) {
                // 关闭失败不影响主流程
            }
        }
        RedisClient client = redisClient;
        redisClient = null;
        if (client != null) {
            try {
                client.shutdown();
            } catch (Exception ignore) {
                // 关闭失败不影响主流程
            }
        }
    }

    private String toJson(Entry entry) {
        JSONObject json = new JSONObject();
        json.put("appId", entry.appId);
        json.put("scope", entry.scope);
        json.put("text", entry.text);
        json.put("totalChars", entry.totalChars);
        json.put("truncated", entry.truncated);
        json.put("createdAt", entry.createdAt);
        return json.toJSONString();
    }

    private Entry fromJson(String json) {
        try {
            JSONObject value = JSON.parseObject(json);
            if (value == null) {
                return null;
            }
            return new Entry(
                    value.getString("appId"),
                    value.getString("scope"),
                    value.getString("text"),
                    value.getIntValue("totalChars"),
                    value.getBooleanValue("truncated"),
                    value.getLongValue("createdAt"));
        } catch (Exception e) {
            logger.warn("[CHUNK] 解析 Redis 暂存结果失败: {}", e.getMessage());
            return null;
        }
    }

    public static final class Entry {
        private final String appId;
        private final String scope;
        private final String text;
        private final int totalChars;
        private final boolean truncated;
        private final long createdAt;

        public Entry(String appId, String scope, String text, int totalChars,
                     boolean truncated, long createdAt) {
            this.appId = appId;
            this.scope = scope;
            this.text = text;
            this.totalChars = totalChars;
            this.truncated = truncated;
            this.createdAt = createdAt;
        }

        public String getAppId() {
            return appId;
        }

        public String getScope() {
            return scope;
        }

        public String getText() {
            return text;
        }

        public int getTotalChars() {
            return totalChars;
        }

        public boolean isTruncated() {
            return truncated;
        }

        public long getCreatedAt() {
            return createdAt;
        }
    }
}
