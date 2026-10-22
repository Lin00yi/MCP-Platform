package com.mcp.transfer.exposure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 列表分页埋点：tools/prompts/resources 发出 nextCursor 后客户端是否真的带 cursor 回来。
 */
@Component
public class ToolPaginationTelemetry {

    private static final Logger logger = LoggerFactory.getLogger(ToolPaginationTelemetry.class);

    private final Map<String, Counters> counters = new ConcurrentHashMap<>();
    private final Map<String, Long> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public ToolPaginationTelemetry() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "asset-pagination-telemetry");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(this::sweep, 10, 10, TimeUnit.SECONDS);
    }

    public void record(String sessionId, boolean cursorUsed, boolean hasNext, String clientName) {
        record(sessionId, "tools", cursorUsed, hasNext, clientName);
    }

    public void record(String sessionId, String kind, boolean cursorUsed, boolean hasNext, String clientName) {
        String type = kind == null ? "tools" : kind;
        Counters c = counters.computeIfAbsent(type, k -> new Counters());
        String key = type + ":" + (sessionId == null ? "anonymous" : sessionId);
        if (cursorUsed) {
            if (pending.remove(key) != null) {
                c.followed.incrementAndGet();
            }
        }
        if (hasNext) {
            c.issued.incrementAndGet();
            if (sessionId != null) {
                pending.put(key, System.currentTimeMillis());
            }
            logger.debug("[ASSET_PAGINATION] 返回 nextCursor, kind={}, client={}, session={}", type, clientName, sessionId);
        }
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : pending.entrySet()) {
            if (now - entry.getValue() < 10_000L) {
                continue;
            }
            if (pending.remove(entry.getKey()) != null) {
                String key = entry.getKey();
                int idx = key.indexOf(':');
                String type = idx > 0 ? key.substring(0, idx) : "tools";
                counters.computeIfAbsent(type, k -> new Counters()).abandoned.incrementAndGet();
                logger.warn("[ASSET_PAGINATION] 客户端未跟随 nextCursor（可能只看到第一页）, kind={}, session={}",
                        type, idx > 0 ? key.substring(idx + 1) : key);
            }
        }
    }

    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        long issued = 0;
        long followed = 0;
        long abandoned = 0;
        Map<String, Object> byKind = new LinkedHashMap<>();
        for (Map.Entry<String, Counters> entry : counters.entrySet()) {
            Counters c = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("cursorIssued", c.issued.get());
            item.put("cursorFollowed", c.followed.get());
            item.put("cursorAbandoned", c.abandoned.get());
            byKind.put(entry.getKey(), item);
            issued += c.issued.get();
            followed += c.followed.get();
            abandoned += c.abandoned.get();
        }
        result.put("cursorIssued", issued);
        result.put("cursorFollowed", followed);
        result.put("cursorAbandoned", abandoned);
        result.put("pending", pending.size());
        result.put("byKind", byKind);
        return result;
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    private static class Counters {
        private final java.util.concurrent.atomic.AtomicLong issued = new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicLong followed = new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicLong abandoned = new java.util.concurrent.atomic.AtomicLong();
    }
}
