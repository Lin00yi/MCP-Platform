package com.mcp.transfer.health;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 单副本选主：永远由当前进程探测。
 */
@Component
@ConditionalOnProperty(name = "mcp.redis.enabled", havingValue = "false", matchIfMissing = true)
public class MemoryProbeLeader implements ProbeLeader {

    @Override
    public boolean isLeader() {
        return true;
    }
}
