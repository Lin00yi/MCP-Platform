package com.mcp.transfer.health;

/**
 * 探测结果缓存。
 *
 * <p>单副本用进程内；多副本用 Redis，让「一个副本探测、所有副本共读」，
 * 上游探测次数不再随副本数线性增长。
 */
public interface ProbeResultCache {

    /** 未命中返回 null */
    ProbeSnapshot get(Integer serverId);

    void put(Integer serverId, ProbeSnapshot snapshot);

    /** 是否跨副本共享 */
    boolean shared();
}
