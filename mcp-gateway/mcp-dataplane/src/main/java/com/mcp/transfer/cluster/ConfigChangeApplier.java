package com.mcp.transfer.cluster;

import com.mcp.transfer.cache.PromptListCache;
import com.mcp.transfer.cache.ResourceListCache;
import com.mcp.transfer.cache.ToolListCache;
import com.mcp.transfer.config.session.SseEmitterRegistry;
import com.mcp.transfer.entity.McpTool;
import com.mcp.transfer.exposure.ToolEmbeddingService;
import com.mcp.transfer.gateway.route.McpServerDynamicControllerManager;
import com.mcp.transfer.mapper.McpToolMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 集群变更事件的应用端：失效本地缓存、刷新动态路由、向本机 SSE 连接广播。
 *
 * <p>每个数据面副本都会收到同一批事件（本地总线或 Redis 频道），
 * 各副本只处理自己的进程内状态，因此天然幂等。
 */
@Component
public class ConfigChangeApplier {

    private static final Logger logger = LoggerFactory.getLogger(ConfigChangeApplier.class);

    @Autowired
    private ClusterEventBus eventBus;

    @Autowired
    private ToolListCache toolListCache;

    @Autowired
    private PromptListCache promptListCache;

    @Autowired
    private ResourceListCache resourceListCache;

    @Autowired
    private SseEmitterRegistry sseEmitterRegistry;

    @Autowired
    private McpServerDynamicControllerManager routeManager;

    /** 向量索引由数据面维护；embedding 未配置时内部自动 no-op */
    @Autowired(required = false)
    private ToolEmbeddingService toolEmbeddingService;

    @Autowired
    private McpToolMapper mcpToolMapper;

    @PostConstruct
    public void subscribe() {
        eventBus.subscribe(this::apply);
        logger.info("[EVENT] 配置变更订阅已就绪（distributed={}）", eventBus.distributed());
    }

    private void apply(ClusterEvent event) {
        if (event == null || event.getType() == null) {
            return;
        }
        Integer serverId = event.getServerId();
        logger.info("[EVENT] 收到变更 type={}, serverId={}", event.getType(), serverId);
        switch (event.getType()) {
            case TOOL:
                toolListCache.invalidate(serverId);
                broadcast("notifications/tools/list_changed", serverId);
                maintainEmbedding(event);
                break;
            case PROMPT:
                promptListCache.invalidate(serverId);
                broadcast("notifications/prompts/list_changed", serverId);
                break;
            case RESOURCE:
                resourceListCache.invalidate(serverId);
                broadcast("notifications/resources/list_changed", serverId);
                break;
            case ROUTES:
                refreshRoutes();
                break;
            case ALL:
                toolListCache.clear();
                promptListCache.clear();
                resourceListCache.clear();
                refreshRoutes();
                broadcast("notifications/tools/list_changed", null);
                broadcast("notifications/prompts/list_changed", null);
                broadcast("notifications/resources/list_changed", null);
                break;
            default:
                break;
        }
    }

    /**
     * 增量维护向量索引：控制面只发「哪个工具、什么操作」，具体调用 embedding 在数据面完成。
     *
     * <p>没有 toolId 时退化为整 server 重建（OpenAPI 批量导入、工具迁移等场景）。
     */
    private void maintainEmbedding(ClusterEvent event) {
        if (toolEmbeddingService == null) {
            return;
        }
        Integer serverId = event.getServerId();
        if (serverId == null) {
            return;
        }
        Integer toolId = event.getToolId();
        String op = event.getOp();
        try {
            if ("PURGE_SERVER".equals(op)) {
                toolEmbeddingService.deleteServer(serverId);
            } else if (toolId != null && "DELETE".equals(op)) {
                toolEmbeddingService.deleteTool(toolId, serverId);
            } else if (toolId != null) {
                McpTool tool = mcpToolMapper.selectByPrimaryKey(toolId);
                if (tool != null) {
                    toolEmbeddingService.indexToolAsync(tool);
                }
            } else {
                toolEmbeddingService.reindexServerAsync(serverId, mcpToolMapper.selectAllByServerId(serverId));
            }
        } catch (Exception e) {
            logger.warn("[EVENT] 向量索引维护失败 serverId={}, toolId={}, op={}: {}",
                    serverId, toolId, op, e.getMessage());
        }
    }

    private void broadcast(String method, Integer serverId) {
        String message = "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\"}";
        try {
            sseEmitterRegistry.broadcastToServer(serverId, message);
            // Endpoint 模式的会话没有单一 serverId，同样需要刷新
            sseEmitterRegistry.broadcastToAggregate(message);
        } catch (Exception e) {
            logger.warn("[EVENT] SSE 广播 {} 失败, serverId={}: {}", method, serverId, e.getMessage());
        }
    }

    private void refreshRoutes() {
        try {
            String result = routeManager.refreshAllServerController();
            logger.info("[EVENT] 路由已刷新：{}", result);
        } catch (Exception e) {
            logger.error("[EVENT] 刷新动态路由失败", e);
        }
    }
}
