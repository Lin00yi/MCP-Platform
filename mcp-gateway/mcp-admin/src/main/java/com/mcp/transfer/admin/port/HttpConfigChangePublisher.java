package com.mcp.transfer.admin.port;

import com.mcp.transfer.port.ConfigChangePublisher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link ConfigChangePublisher} 的远程实现：通过内部接口通知数据面。
 *
 * <p>关键改动（性能/稳定性）：
 * <ul>
 *   <li><b>事务提交后再通知</b>：写接口是 {@code @Transactional}，原先在事务内同步 POST，
 *       数据面卡顿会把 DB 事务/连接占住最长 63s，且通知先于 commit 会让数据面回填旧数据。
 *       现在若存在 Spring 事务，注册 {@code afterCommit} 回调；无事务则直接异步提交；</li>
 *   <li><b>异步 + 短超时</b>：单线程有界队列执行器，通知用 1s/2s 的专用 RestTemplate，</li>
 *   <li><b>失败只 WARN 不抛出</b>：缓存失效最终由数据面 revision 兜底轮询。</li>
 * </ul>
 */
@Component
public class HttpConfigChangePublisher implements ConfigChangePublisher {

    private static final Logger logger = LoggerFactory.getLogger(HttpConfigChangePublisher.class);

    /** 通知队列容量：瞬时写操作远小于该值，满则丢弃（有 revision 兜底） */
    private static final int QUEUE_CAPACITY = 1000;

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String internalToken;

    private ThreadPoolExecutor executor;

    private final AtomicLong published = new AtomicLong();
    private final AtomicLong discarded = new AtomicLong();

    public HttpConfigChangePublisher(@Qualifier("configChangeRestTemplate") RestTemplate restTemplate,
                                     @Value("${mcp.admin.dataplane-url:http://127.0.0.1:8081}") String baseUrl,
                                     @Value("${mcp.internal.token:}") String internalToken) {
        this.restTemplate = restTemplate;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.internalToken = internalToken;
    }

    @PostConstruct
    public void start() {
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "config-change-notifier");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.DiscardPolicy());
    }

    @PreDestroy
    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(2L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void toolChanged(Integer serverId) {
        publish("tool", serverId);
    }

    @Override
    public void toolChanged(Integer serverId, Integer toolId, String op) {
        publish("tool", serverId, toolId, op);
    }

    @Override
    public void promptChanged(Integer serverId) {
        publish("prompt", serverId);
    }

    @Override
    public void resourceChanged(Integer serverId) {
        publish("resource", serverId);
    }

    @Override
    public void serverChanged(Integer serverId) {
        publish("server", serverId);
    }

    private void publish(String changeType, Integer serverId) {
        publish(changeType, serverId, null, null);
    }

    private void publish(String changeType, Integer serverId, Integer toolId, String op) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("changeType", changeType);
        body.put("serverId", serverId);
        body.put("toolId", toolId);
        body.put("op", op);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 事务提交后再发：避免通知先于 commit，也避免把 DB 事务/连接绑在 HTTP 调用上
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enqueue(changeType, serverId, body);
                }
            });
            return;
        }
        enqueue(changeType, serverId, body);
    }

    private void enqueue(String changeType, Integer serverId, Map<String, Object> body) {
        ThreadPoolExecutor pool = this.executor;
        if (pool == null || pool.isShutdown()) {
            logger.debug("[CHANGE] 通知执行器已停止，忽略 changeType={}", changeType);
            return;
        }
        if (pool.getQueue().remainingCapacity() == 0) {
            discarded.incrementAndGet();
            logger.warn("[CHANGE] 通知队列已满，丢弃 changeType={}（数据面 revision 轮询会兜底）", changeType);
            return;
        }
        pool.execute(() -> send(changeType, serverId, body));
        published.incrementAndGet();
    }

    private void send(String changeType, Integer serverId, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (internalToken != null && !internalToken.trim().isEmpty()) {
            headers.set("X-Internal-Token", internalToken);
        }
        try {
            restTemplate.postForEntity(baseUrl + "/internal/config-changed",
                    new HttpEntity<>(body, headers), Map.class);
        } catch (Exception e) {
            logger.warn("[CHANGE] 通知数据面失败 changeType={}, serverId={}: {}（管理端写操作已生效）",
                    changeType, serverId, e.getMessage());
        }
    }

    /** 异步通知队列指标（排障用） */
    public Map<String, Long> stats() {
        Map<String, Long> map = new LinkedHashMap<>();
        map.put("published", published.get());
        map.put("discarded", discarded.get());
        map.put("pending", executor == null ? 0L : (long) executor.getQueue().size());
        return map;
    }

    private static String trimTrailingSlash(String url) {
        String value = url == null ? "" : url.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
