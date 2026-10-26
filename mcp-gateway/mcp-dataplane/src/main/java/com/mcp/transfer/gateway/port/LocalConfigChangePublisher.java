package com.mcp.transfer.gateway.port;

import com.mcp.transfer.cluster.ClusterEvent;
import com.mcp.transfer.cluster.ClusterEventBus;
import com.mcp.transfer.port.ConfigChangePublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@link ConfigChangePublisher} 的数据面实现：把变更事件投递到集群总线。
 *
 * <p>不直接操作缓存/SSE —— 这些由 {@code ConfigChangeApplier} 在收到事件后处理，
 * 这样单副本（进程内总线）与多副本（Redis 总线）走同一条代码路径。
 */
@Component
public class LocalConfigChangePublisher implements ConfigChangePublisher {

    @Autowired
    private ClusterEventBus eventBus;

    @Override
    public void toolChanged(Integer serverId) {
        eventBus.publish(ClusterEvent.of(ClusterEvent.Type.TOOL, serverId));
    }

    @Override
    public void toolChanged(Integer serverId, Integer toolId, String op) {
        eventBus.publish(ClusterEvent.tool(serverId, toolId, op));
    }

    @Override
    public void promptChanged(Integer serverId) {
        eventBus.publish(ClusterEvent.of(ClusterEvent.Type.PROMPT, serverId));
    }

    @Override
    public void resourceChanged(Integer serverId) {
        eventBus.publish(ClusterEvent.of(ClusterEvent.Type.RESOURCE, serverId));
    }

    @Override
    public void serverChanged(Integer serverId) {
        eventBus.publish(ClusterEvent.of(ClusterEvent.Type.SERVER, serverId));
    }
}
