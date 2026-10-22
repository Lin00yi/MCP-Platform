package com.mcp.transfer.cluster;

import java.util.function.Consumer;

/**
 * 集群事件总线：把「资产变更」广播到所有数据面副本。
 *
 * <p>单副本：{@link LocalClusterEventBus}（进程内同步回调）；
 * 多副本：{@link RedisClusterEventBus}（Redis Pub/Sub，每副本都会收到）。
 */
public interface ClusterEventBus {

    void publish(ClusterEvent event);

    void subscribe(Consumer<ClusterEvent> consumer);

    /** 是否为跨副本广播 */
    boolean distributed();
}
