package com.mcp.transfer.health;

/**
 * 探活选主：多副本部署时只让一个副本执行定时探测，避免上游被 N 倍打。
 *
 * <p>单副本实现永远为 true；Redis 实现用 {@code SET NX PX} 抢租约。
 */
public interface ProbeLeader {

    /** 当前副本是否应该执行定时探测 */
    boolean isLeader();
}
