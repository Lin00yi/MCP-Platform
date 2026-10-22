package com.mcp.transfer.health;

import com.mcp.transfer.redis.RedisProperties;
import com.mcp.transfer.redis.RedisSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 多副本探活的契约测试：探测结果共享 + 选主互斥。
 *
 * <p>没有 Redis 时整类跳过（assumeTrue），不会让普通构建变红。
 */
class RedisProbeClusterTest {

    private static final String URI = System.getenv().getOrDefault("MCP_TEST_REDIS_URI", "redis://127.0.0.1:6379/14");

    private RedisSupport support;
    private RedisProperties properties;
    private RedisProbeResultCache cacheA;
    private RedisProbeResultCache cacheB;

    @BeforeEach
    void setUp() {
        properties = new RedisProperties();
        properties.setEnabled(true);
        properties.setUri(URI);
        properties.setKeyPrefix("mcp-test-health:");
        support = new RedisSupport(properties);
        try {
            support.commands().ping();
        } catch (Exception e) {
            assumeTrue(false, "Redis 不可用，跳过: " + e.getMessage());
        }
        cleanup();

        cacheA = new RedisProbeResultCache(support, properties);
        cacheB = new RedisProbeResultCache(support, properties);
        ReflectionTestUtils.setField(cacheA, "ttlSeconds", 90L);
        ReflectionTestUtils.setField(cacheB, "ttlSeconds", 90L);
    }

    @AfterEach
    void tearDown() {
        if (support != null) {
            cleanup();
            support.shutdown();
        }
    }

    private void cleanup() {
        try {
            io.lettuce.core.api.sync.RedisCommands<String, String> cmd = support.commands();
            for (String key : cmd.keys(properties.getKeyPrefix() + "*")) {
                cmd.del(key);
            }
        } catch (Exception ignore) {
            // ignore
        }
    }

    @Test
    void probeResultWrittenByLeaderIsReadableByFollower() {
        cacheA.put(7, new ProbeSnapshot(ProbeResult.ok(11, "leader-probed"), System.currentTimeMillis()));

        ProbeSnapshot seenByB = cacheB.get(7);
        assertNotNull(seenByB);
        assertTrue(seenByB.result.ok);
        assertEquals(11, seenByB.result.latencyMs);
        assertTrue(cacheB.shared());
        assertFalse(cacheA.get(999) != null);
    }

    @Test
    void jsonFormatIsStableAcrossInstances() {
        cacheA.put(8, new ProbeSnapshot(ProbeResult.fail(20, "timeout"), 1700000000000L));
        ProbeSnapshot snapshot = cacheB.get(8);

        assertNotNull(snapshot);
        assertFalse(snapshot.result.ok);
        assertEquals("timeout", snapshot.result.message);
        assertEquals(1700000000000L, snapshot.checkedAt);
    }

    @Test
    void onlyOneReplicaBecomesProbeLeader() {
        RedisProbeLeader leaderA = new RedisProbeLeader(support, properties);
        RedisProbeLeader leaderB = new RedisProbeLeader(support, properties);

        assertTrue(leaderA.isLeader(), "第一个副本应拿到租约");
        assertFalse(leaderB.isLeader(), "第二个副本不应抢到租约");
        assertTrue(leaderA.isLeader(), "持有者再次检查应续期成功");
    }

    @Test
    void leaseExpiresAndAnotherReplicaTakesOver() throws Exception {
        RedisProbeLeader leaderA = new RedisProbeLeader(support, properties);
        assertTrue(leaderA.isLeader());

        // 模拟 leader 进程已死：租约 TTL 到期
        String key = properties.key("health:leader");
        support.commands().pexpire(key, 1);
        Thread.sleep(50);

        RedisProbeLeader leaderB = new RedisProbeLeader(support, properties);
        assertTrue(leaderB.isLeader(), "租约过期后应能接管");
    }
}
