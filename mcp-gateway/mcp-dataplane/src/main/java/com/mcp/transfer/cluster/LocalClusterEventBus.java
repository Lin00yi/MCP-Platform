package com.mcp.transfer.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 进程内事件总线（默认实现，单副本部署）。
 *
 * <p>publish 同步回调订阅者，行为与拆分前一致：管理端改完立即失效缓存并广播 SSE。
 */
@Component
@ConditionalOnProperty(name = "mcp.redis.enabled", havingValue = "false", matchIfMissing = true)
public class LocalClusterEventBus implements ClusterEventBus {

    private static final Logger logger = LoggerFactory.getLogger(LocalClusterEventBus.class);

    private final List<Consumer<ClusterEvent>> consumers = new CopyOnWriteArrayList<>();

    @Override
    public void publish(ClusterEvent event) {
        if (event == null) {
            return;
        }
        for (Consumer<ClusterEvent> consumer : consumers) {
            try {
                consumer.accept(event);
            } catch (Exception e) {
                logger.warn("[EVENT] 本地事件处理失败 type={}, serverId={}: {}",
                        event.getType(), event.getServerId(), e.getMessage(), e);
            }
        }
    }

    @Override
    public void subscribe(Consumer<ClusterEvent> consumer) {
        if (consumer != null) {
            consumers.add(consumer);
        }
    }

    @Override
    public boolean distributed() {
        return false;
    }
}
