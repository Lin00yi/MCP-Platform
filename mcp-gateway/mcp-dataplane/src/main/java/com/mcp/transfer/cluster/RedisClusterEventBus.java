package com.mcp.transfer.cluster;

import com.mcp.transfer.redis.RedisProperties;
import com.mcp.transfer.redis.RedisSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Redis Pub/Sub 事件总线（多副本部署）。
 *
 * <p>发布方只写一次频道，所有副本（含自己）都会收到并各自失效本地缓存、
 * 向本机 SSE 连接广播 {@code notifications/*_changed}。
 *
 * <p>降级：Redis 发布失败时直接在本进程内分发，至少保证当前副本的缓存正确。
 */
@Component
@ConditionalOnProperty(name = "mcp.redis.enabled", havingValue = "true")
public class RedisClusterEventBus implements ClusterEventBus {

    private static final Logger logger = LoggerFactory.getLogger(RedisClusterEventBus.class);

    private final RedisSupport redis;
    private final RedisProperties properties;

    public RedisClusterEventBus(RedisSupport redis, RedisProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    private final List<Consumer<ClusterEvent>> consumers = new CopyOnWriteArrayList<>();

    @PostConstruct
    public void init() {
        boolean subscribed = redis.subscribe(properties.getEventChannel(), body -> {
            ClusterEvent event = ClusterEvent.fromJson(body);
            if (event != null) {
                dispatch(event);
            }
        });
        if (!subscribed) {
            logger.warn("[EVENT] Redis 订阅未建立，变更事件只能在当前副本生效");
        }
    }

    @Override
    public void publish(ClusterEvent event) {
        if (event == null) {
            return;
        }
        try {
            redis.publish(properties.getEventChannel(), event.toJson());
        } catch (Exception e) {
            logger.warn("[EVENT] Redis 发布失败，降级为本地分发 type={}: {}", event.getType(), e.getMessage());
            dispatch(event);
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
        return true;
    }

    private void dispatch(ClusterEvent event) {
        for (Consumer<ClusterEvent> consumer : consumers) {
            try {
                consumer.accept(event);
            } catch (Exception e) {
                logger.warn("[EVENT] 事件处理失败 type={}, serverId={}: {}",
                        event.getType(), event.getServerId(), e.getMessage(), e);
            }
        }
    }
}
