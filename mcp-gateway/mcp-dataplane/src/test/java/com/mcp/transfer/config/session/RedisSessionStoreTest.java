package com.mcp.transfer.config.session;

import com.mcp.transfer.redis.RedisProperties;
import com.mcp.transfer.redis.RedisSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Redis 会话存储的契约测试：两个 store 实例模拟两个数据面副本。
 *
 * <p>本地/CI 没有 Redis 时整类跳过（{@code assumeTrue}），不会让构建变红。
 */
class RedisSessionStoreTest {

    private static final String URI = System.getenv().getOrDefault("MCP_TEST_REDIS_URI", "redis://127.0.0.1:6379/15");

    private RedisSupport support;
    private RedisProperties properties;
    private RedisSessionStore replicaA;
    private RedisSessionStore replicaB;

    @BeforeEach
    void setUp() {
        properties = new RedisProperties();
        properties.setEnabled(true);
        properties.setUri(URI);
        properties.setKeyPrefix("mcp-test-session:");
        support = new RedisSupport(properties);
        try {
            support.commands().ping();
        } catch (Exception e) {
            assumeTrue(false, "Redis 不可用，跳过: " + e.getMessage());
        }
        replicaA = new RedisSessionStore(support, properties);
        replicaB = new RedisSessionStore(support, properties);
    }

    @AfterEach
    void tearDown() {
        if (support != null) {
            try {
                // 只删本测试前缀的 key，避免清空共享 Redis
                io.lettuce.core.api.sync.RedisCommands<String, String> cmd = support.commands();
                for (String key : cmd.keys(properties.getKeyPrefix() + "*")) {
                    cmd.del(key);
                }
            } catch (Exception ignore) {
                // ignore
            }
            support.shutdown();
        }
    }

    @Test
    void sessionWrittenByOneReplicaIsVisibleToAnother() {
        SessionInfo info = new SessionInfo(System.currentTimeMillis() + 60_000);
        info.setClientName("dsh");
        info.setUserId("u-1");

        replicaA.save("sid-shared", info, 60_000);

        SessionInfo seenByB = replicaB.find("sid-shared");
        assertNotNull(seenByB);
        assertEquals("dsh", seenByB.getClientName());
        assertEquals("u-1", seenByB.getUserId());
        assertTrue(replicaB.shared());
    }

    @Test
    void removalOnOneReplicaAppliesToAll() {
        replicaA.save("sid-del", new SessionInfo(System.currentTimeMillis() + 60_000), 60_000);
        assertNotNull(replicaB.find("sid-del"));

        replicaB.remove("sid-del");
        assertNull(replicaA.find("sid-del"));
    }

    @Test
    void ttlIsEnforcedByRedis() throws Exception {
        replicaA.save("sid-ttl", new SessionInfo(System.currentTimeMillis() + 1_000), 1_000);
        long ttl = support.commands().ttl(properties.key("session:sid-ttl"));
        assertTrue(ttl > 0 && ttl <= 1, "TTL 应为 1s，实际 " + ttl);
    }

    @Test
    void missingSessionReturnsNull() {
        assertNull(replicaA.find("does-not-exist"));
        assertNull(replicaA.find(null));
    }
}
