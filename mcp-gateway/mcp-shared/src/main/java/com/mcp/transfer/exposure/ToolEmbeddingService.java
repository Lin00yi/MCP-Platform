package com.mcp.transfer.exposure;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mcp.transfer.shared.entity.McpTool;
import com.mcp.transfer.shared.entity.ToolEmbedding;
import com.mcp.transfer.shared.mapper.McpToolMapper;
import com.mcp.transfer.shared.mapper.ToolEmbeddingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工具描述向量索引：异步计算、按 server 内存缓存、查询时余弦相似度。
 *
 * <p>当前规模（数千工具）不引入向量数据库：每个 server 的向量常驻内存，
 * 查询时暴力余弦，毫秒级；embedding 不可用时完全降级到词法检索。
 */
@Component
public class ToolEmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(ToolEmbeddingService.class);

    @Autowired(required = false)
    private EmbeddingClient embeddingClient;

    @Autowired
    private ToolEmbeddingMapper toolEmbeddingMapper;

    @Autowired
    private McpToolMapper mcpToolMapper;

    @Autowired
    private EmbeddingProperties embeddingProperties;

    /** embedding 调用失败后的降级窗口：窗口内直接返回 null（检索层降级 BM25），不再每个请求都重试 */
    private static final long FAIL_BACKOFF_MS = 30_000L;

    private final Map<Integer, Index> indexes = new ConcurrentHashMap<>();
    /** 查询向量缓存：有界 LRU（200 条、10 分钟），不再「超限整表 clear」造成缓存抖动 */
    private final Cache<String, float[]> queryCache = Caffeine.newBuilder()
            .maximumSize(200)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();
    private final AtomicLong indexedCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();
    /** 查询向量化失败后的降级截止时间（毫秒时间戳） */
    private volatile long embedFailUntil;
    private volatile long lastFailWarnAt;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "tool-embedding-indexer");
        thread.setDaemon(true);
        return thread;
    });

    public boolean enabled() {
        return embeddingClient != null && embeddingClient.enabled();
    }

    public void indexToolAsync(final McpTool tool) {
        if (!enabled() || tool == null || tool.getId() == null) {
            return;
        }
        executor.submit(() -> indexTool(tool));
    }

    public void indexTool(McpTool tool) {
        if (!enabled() || tool == null || tool.getId() == null) {
            return;
        }
        try {
            float[] vector = embeddingClient.embed(searchText(tool));
            if (vector == null || vector.length == 0) {
                failedCount.incrementAndGet();
                return;
            }
            ToolEmbedding embedding = new ToolEmbedding();
            embedding.setToolId(tool.getId());
            embedding.setServerId(tool.getServerId());
            embedding.setModel(embeddingProperties.getModel());
            embedding.setDim(vector.length);
            embedding.setVector(JSON.toJSONString(vector));
            embedding.setUpdateTime(new Date());
            toolEmbeddingMapper.upsert(embedding);
            indexedCount.incrementAndGet();
            indexes.remove(tool.getServerId());
        } catch (Exception e) {
            failedCount.incrementAndGet();
            logger.warn("[EMBEDDING] 工具 {} 向量化失败: {}", tool.getCode(), e.getMessage());
        }
    }

    public void deleteTool(Integer toolId, Integer serverId) {
        if (toolId == null) {
            return;
        }
        try {
            toolEmbeddingMapper.deleteByToolId(toolId);
            if (serverId != null) {
                indexes.remove(serverId);
            }
        } catch (Exception e) {
            logger.warn("[EMBEDDING] 删除工具向量失败: {}", e.getMessage());
        }
    }

    public void deleteServer(Integer serverId) {
        if (serverId == null) {
            return;
        }
        try {
            toolEmbeddingMapper.deleteByServerId(serverId);
            indexes.remove(serverId);
        } catch (Exception e) {
            logger.warn("[EMBEDDING] 删除 Server 向量失败: {}", e.getMessage());
        }
    }

    public void reindexServerAsync(final Integer serverId, final List<McpTool> tools) {
        if (!enabled() || serverId == null || tools == null) {
            return;
        }
        executor.submit(() -> {
            for (McpTool tool : tools) {
                indexTool(tool);
            }
            indexes.remove(serverId);
        });
    }

    public Map<String, Object> reindexServerSync(Integer serverId) {
        Map<String, Object> result = new HashMap<>();
        result.put("enabled", enabled());
        result.put("serverId", serverId);
        if (!enabled()) {
            result.put("indexed", 0);
            result.put("message", "embedding 未配置（host/key 为空），检索将使用词法匹配");
            return result;
        }
        indexes.remove(serverId);
        List<McpTool> tools = mcpToolMapper.selectAllByServerId(serverId);
        for (McpTool tool : tools) {
            indexTool(tool);
        }
        indexes.remove(serverId);
        Index index = loadIndex(serverId);
        result.put("indexed", index.vectors.size());
        result.put("dim", index.dim);
        result.put("model", embeddingProperties.getModel());
        return result;
    }

    public boolean hasIndex(Integer serverId) {
        return serverId != null && !loadIndex(serverId).vectors.isEmpty();
    }

    public float[] vector(Integer serverId, Integer toolId) {
        if (serverId == null || toolId == null) {
            return null;
        }
        return loadIndex(serverId).vectors.get(toolId);
    }

    /**
     * 查询向量：有界 LRU 缓存；embedding 失败后 30s 内直接返回 null（检索降级 BM25），
     * 避免检索链路每个请求都要等一次 3s 超时重试。
     */
    public float[] queryVector(String query) {
        if (!enabled() || !StringUtils.hasText(query)) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now < embedFailUntil) {
            return null;
        }
        float[] cached = queryCache.getIfPresent(query);
        if (cached != null) {
            return cached;
        }
        float[] vector = embeddingClient.embed(query);
        if (vector == null) {
            embedFailUntil = now + FAIL_BACKOFF_MS;
            long last = lastFailWarnAt;
            if (now - last > 60_000L) {
                lastFailWarnAt = now;
                failedCount.incrementAndGet();
                logger.warn("[EMBEDDING] 查询向量化失败，{}s 内检索降级为 BM25（不再逐请求重试）",
                        FAIL_BACKOFF_MS / 1000);
            }
            return null;
        }
        queryCache.put(query, vector);
        return vector;
    }

    public Map<String, Object> stats() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", enabled());
        map.put("model", embeddingProperties.getModel());
        map.put("indexed", indexedCount.get());
        map.put("failed", failedCount.get());
        map.put("cachedServers", indexes.size());
        return map;
    }

    private Index loadIndex(Integer serverId) {
        Index index = indexes.get(serverId);
        if (index != null) {
            return index;
        }
        Index loaded = new Index();
        try {
            List<ToolEmbedding> rows = toolEmbeddingMapper.selectByServerId(serverId);
            if (rows != null) {
                for (ToolEmbedding row : rows) {
                    float[] vector = parse(row.getVector());
                    if (vector != null) {
                        loaded.vectors.put(row.getToolId(), vector);
                        loaded.dim = vector.length;
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("[EMBEDDING] 加载向量索引失败, server={}: {}", serverId, e.getMessage());
        }
        indexes.put(serverId, loaded);
        return loaded;
    }

    private float[] parse(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            JSONArray array = JSON.parseArray(json);
            if (array == null) {
                return null;
            }
            float[] vector = new float[array.size()];
            for (int i = 0; i < array.size(); i++) {
                vector[i] = array.getFloatValue(i);
            }
            return vector;
        } catch (Exception e) {
            return null;
        }
    }

    private String searchText(McpTool tool) {
        return tool.getCode() + " " + (tool.getName() == null ? "" : tool.getName())
                + " " + ToolDefinitionSupport.description(tool)
                + " " + (tool.getTags() == null ? "" : tool.getTags());
    }

    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) {
            return 0;
        }
        int len = Math.min(a.length, b.length);
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < len; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private static class Index {
        private final Map<Integer, float[]> vectors = new ConcurrentHashMap<>();
        private volatile int dim;
    }
}
