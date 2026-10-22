package com.mcp.transfer.redis;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Redis 配置（多副本部署用）。
 *
 * <p>默认 {@code mcp.redis.enabled=false}：单副本部署不需要 Redis，
 * 所有集群能力自动退化为进程内实现。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mcp.redis")
public class RedisProperties {

    /** 总开关：开启后会话、上游会话、变更事件走 Redis */
    private boolean enabled = false;

    /** Redis 连接串 */
    private String uri = "redis://127.0.0.1:6379/0";

    /** key 前缀，多环境共用一个 Redis 时用于隔离 */
    private String keyPrefix = "mcp:";

    /** 变更事件频道（不含前缀；两种实现都用同一个频道名） */
    private String eventChannel = "events:config";

    /** 会话 TTL（秒），需与 MCP session 过期时间一致 */
    private long sessionTtlSeconds = 1800;

    public String key(String suffix) {
        String prefix = keyPrefix == null ? "" : keyPrefix;
        return prefix + suffix;
    }
}
