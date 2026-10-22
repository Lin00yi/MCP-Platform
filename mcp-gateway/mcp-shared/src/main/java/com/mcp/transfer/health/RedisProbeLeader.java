package com.mcp.transfer.health;

import com.mcp.transfer.redis.RedisProperties;
import com.mcp.transfer.redis.RedisSupport;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.util.UUID;

/**
 * Redis 租约选主：所有数据面副本每 30s 抢一次 {@code mcp:health:leader}。
 *
 * <p>规则：
 * <ul>
 *   <li>租约属于自己 → 续期，继续探测；</li>
 *   <li>租约空闲 → {@code SET NX PX} 抢占，抢到即当 leader；</li>
 *   <li>租约属于别人 → 放弃探测，直接读别人写进 Redis 的结果；</li>
 *   <li>Redis 不可用 → <b>退化为 all-probe</b>（宁可多打上游，也不要健康数据全空）。</li>
 * </ul>
 *
 * <p>租约 TTL 45s 大于探测间隔 30s：leader 正常运行时不会丢租约，
 * 进程挂掉后最多 45s 由另一个副本接管。
 */
@Component
@ConditionalOnProperty(name = "mcp.redis.enabled", havingValue = "true")
public class RedisProbeLeader implements ProbeLeader {

    private static final Logger logger = LoggerFactory.getLogger(RedisProbeLeader.class);

    private static final String LEADER_KEY = "health:leader";

    private final RedisSupport redis;
    private final RedisProperties properties;
    private final String instanceId;

    @Value("${mcp.health.leader-ttl-seconds:45}")
    private long ttlSeconds;

    /** Redis 故障告警节流 */
    private volatile long lastWarnAt;
    /** 上一次的选主结果，用于只在状态变化时打日志 */
    private volatile Boolean lastResult;

    public RedisProbeLeader(RedisSupport redis, RedisProperties properties) {
        this.redis = redis;
        this.properties = properties;
        this.instanceId = ManagementFactory.getRuntimeMXBean().getName() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    public boolean isLeader() {
        try {
            RedisCommands<String, String> cmd = redis.commands();
            String key = properties.key(LEADER_KEY);
            long ttlMs = Math.max(5_000L, ttlSeconds * 1000L);

            String holder = cmd.get(key);
            if (instanceId.equals(holder)) {
                cmd.pexpire(key, ttlMs);
                return logAndReturn(true);
            }
            String acquired = cmd.set(key, instanceId, SetArgs.Builder.nx().px(ttlMs));
            if ("OK".equalsIgnoreCase(acquired) || instanceId.equals(cmd.get(key))) {
                return logAndReturn(true);
            }
            return logAndReturn(false);
        } catch (Exception e) {
            long now = System.currentTimeMillis();
            if (now - lastWarnAt > 60_000L) {
                lastWarnAt = now;
                logger.warn("[HEALTH] 选主失败（{}），本次退化为本副本探测", e.getMessage());
            }
            return true;
        }
    }

    private boolean logAndReturn(boolean leader) {
        Boolean previous = lastResult;
        if (previous == null || previous != leader) {
            if (leader) {
                logger.info("[HEALTH] 本副本成为探活 leader（instance={}）", instanceId);
            } else {
                logger.info("[HEALTH] 本副本不是探活 leader，改为读取其它副本的探测结果");
            }
            lastResult = leader;
        }
        return leader;
    }
}
