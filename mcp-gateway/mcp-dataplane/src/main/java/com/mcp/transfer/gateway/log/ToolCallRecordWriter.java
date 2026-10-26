package com.mcp.transfer.gateway.log;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mcp.transfer.shared.entity.Tenant;
import com.mcp.transfer.shared.entity.ToolCallRecord;
import com.mcp.transfer.shared.mapper.TenantMapper;
import com.mcp.transfer.shared.mapper.ToolCallRecordMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工具调用记录的异步批量写入器。
 *
 * <p>背景：调用记录原先在 {@code finally} 里同步 insert，每个 {@code tools/call} 都要多等
 * 一次 SELECT（查租户名）+ 一次最大 120KB 的 INSERT。这里改为：
 * <ul>
 *   <li>请求线程只做「构造 record + 入有界队列」，不阻塞、不访问 DB；</li>
 *   <li>单线程守护 worker 每 {@value #FLUSH_INTERVAL_MS}ms 或满 {@value #BATCH_SIZE} 条批量 flush；</li>
 *   <li>队列满时丢弃并限频 WARN（宁可丢调用日志，也不能拖慢请求）；</li>
 *   <li>租户名走 10 分钟本地缓存，消掉每次调用一次 SELECT。</li>
 * </ul>
 *
 * <p>注意：record 必须在 {@link #offer(ToolCallRecord)} 之前构造完成（不依赖请求线程后续状态）。
 */
@Component
public class ToolCallRecordWriter {

    private static final Logger logger = LoggerFactory.getLogger(ToolCallRecordWriter.class);

    /** 队列容量：按峰值 10k 待写记录估算，超出即丢弃 */
    private static final int QUEUE_CAPACITY = 10_000;

    /** 单批最大条数，避免单条 multi-value INSERT 过大 */
    private static final int BATCH_SIZE = 200;

    /** flush 间隔 */
    private static final long FLUSH_INTERVAL_MS = 500L;

    /** 丢弃告警限频 */
    private static final long WARN_INTERVAL_MS = 60_000L;

    private final BlockingQueue<ToolCallRecord> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    private final AtomicLong enqueued = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    private volatile long lastWarnAt;

    private volatile boolean running;

    private Thread worker;

    @Autowired
    private ToolCallRecordMapper toolCallRecordMapper;

    @Autowired
    private TenantMapper tenantMapper;

    /** tenantId -> 租户名（可空）；10 分钟 TTL，避免每次调用都查库 */
    private final Cache<Integer, Optional<String>> tenantNameCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(10_000)
            .build();

    @PostConstruct
    public void start() {
        running = true;
        worker = new Thread(this::loop, "tool-call-record-writer");
        worker.setDaemon(true);
        worker.start();
        logger.info("[CALL_RECORD] 异步写入已启动：queue={}, batch={}, flush={}ms",
                QUEUE_CAPACITY, BATCH_SIZE, FLUSH_INTERVAL_MS);
    }

    /** 投递一条调用记录；队列满直接丢弃（不阻塞请求线程） */
    public void offer(ToolCallRecord record) {
        if (record == null) {
            return;
        }
        if (!queue.offer(record)) {
            long total = dropped.incrementAndGet();
            long now = System.currentTimeMillis();
            if (now - lastWarnAt > WARN_INTERVAL_MS) {
                lastWarnAt = now;
                logger.warn("[CALL_RECORD] 写入队列已满（容量 {}），已丢弃累计 {} 条调用记录；"
                        + "请检查数据库是否变慢或调用量是否突增", QUEUE_CAPACITY, total);
            }
        } else {
            enqueued.incrementAndGet();
        }
    }

    /** 租户名（带本地缓存）；tenantId 为空或租户不存在返回 null */
    public String tenantName(Integer tenantId) {
        if (tenantId == null) {
            return null;
        }
        return tenantNameCache.get(tenantId, id -> {
            try {
                Tenant tenant = tenantMapper.selectByPrimaryKey(id);
                return Optional.ofNullable(tenant == null ? null : tenant.getName());
            } catch (Exception e) {
                logger.warn("[CALL_RECORD] 查询租户名失败 tenantId={}: {}", id, e.getMessage());
                return Optional.empty();
            }
        }).orElse(null);
    }

    /** 运行指标（供排障/健康检查读取） */
    public Map<String, Long> stats() {
        Map<String, Long> map = new LinkedHashMap<>();
        map.put("queued", (long) queue.size());
        map.put("enqueued", enqueued.get());
        map.put("written", written.get());
        map.put("dropped", dropped.get());
        map.put("failed", failed.get());
        return map;
    }

    private void loop() {
        List<ToolCallRecord> batch = new ArrayList<>(BATCH_SIZE);
        while (running || !queue.isEmpty()) {
            try {
                ToolCallRecord first = queue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, BATCH_SIZE - 1);
                }
                if (!batch.isEmpty()) {
                    flush(batch);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // 单轮异常不能让 worker 退出，否则后续记录永远不再落库
                logger.warn("[CALL_RECORD] 异步写入循环异常: {}", e.getMessage());
                batch.clear();
                sleepQuietly();
            }
        }
    }

    private void flush(List<ToolCallRecord> batch) {
        int size = batch.size();
        try {
            int rows = toolCallRecordMapper.insertBatch(batch);
            written.addAndGet(Math.max(rows, 0));
        } catch (Exception e) {
            failed.addAndGet(size);
            logger.warn("[CALL_RECORD] 批量写入失败，丢弃 {} 条调用记录: {}", size, e.getMessage());
        } finally {
            batch.clear();
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(200L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 停机：停止接收、把队列里剩余记录尽量写完（最多等 3s） */
    @PreDestroy
    public void shutdown() {
        running = false;
        if (worker != null) {
            try {
                worker.join(3000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        List<ToolCallRecord> rest = new ArrayList<>();
        queue.drainTo(rest);
        if (!rest.isEmpty()) {
            try {
                toolCallRecordMapper.insertBatch(rest);
                written.addAndGet(rest.size());
            } catch (Exception e) {
                logger.warn("[CALL_RECORD] 停机 flush 失败，丢弃 {} 条: {}", rest.size(), e.getMessage());
            }
        }
        logger.info("[CALL_RECORD] 异步写入已停止：enqueued={}, written={}, dropped={}, failed={}",
                enqueued.get(), written.get(), dropped.get(), failed.get());
    }
}
