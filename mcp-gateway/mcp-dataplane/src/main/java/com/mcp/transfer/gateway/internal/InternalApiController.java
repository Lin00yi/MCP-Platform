package com.mcp.transfer.gateway.internal;

import com.alibaba.fastjson.JSON;
import com.mcp.transfer.entity.McpServer;
import com.mcp.transfer.entity.McpTool;
import com.mcp.transfer.health.McpServerHealthService;
import com.mcp.transfer.exposure.ToolEmbeddingService;
import com.mcp.transfer.health.ProbeLeader;
import com.mcp.transfer.mapper.McpServerMapper;
import com.mcp.transfer.cluster.ClusterEventBus;
import com.mcp.transfer.config.session.SessionStore;
import com.mcp.transfer.gateway.port.LocalConfigChangePublisher;
import com.mcp.transfer.gateway.service.McpService;
import com.mcp.transfer.redis.RedisProperties;
import com.mcp.transfer.redis.RedisSupport;
import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据面内部接口（仅供控制面调用，不对外暴露）。
 *
 * <p>Caddy 只转发 {@code /mcp/*} 与 {@code /api/*}，本前缀不会被公网路由到；
 * 另外再做一层 {@code X-Internal-Token} 校验：
 * <ul>
 *   <li>配置了 {@code mcp.internal.token} → 必须完全匹配；</li>
 *   <li>未配置 → 只允许回环地址访问（本地开发），其余一律 403。</li>
 * </ul>
 */
@Hidden
@RestController
@RequestMapping("/internal")
public class InternalApiController {

    private static final Logger logger = LoggerFactory.getLogger(InternalApiController.class);

    @Value("${mcp.internal.token:}")
    private String internalToken;

    @Autowired
    private LocalConfigChangePublisher changePublisher;

    @Autowired
    private McpService mcpService;

    @Autowired
    private SessionStore sessionStore;

    @Autowired
    private ClusterEventBus eventBus;

    @Autowired
    private RedisProperties redisProperties;

    @Autowired
    private RedisSupport redisSupport;

    @Autowired
    private McpServerHealthService healthService;

    @Autowired
    private ProbeLeader probeLeader;

    @Autowired(required = false)
    private ToolEmbeddingService toolEmbeddingService;

    @Autowired
    private McpServerMapper mcpServerMapper;

    /** 集群自检：确认当前副本的会话存储与事件总线是否已分布式化。 */
    @GetMapping("/cluster-info")
    public Map<String, Object> clusterInfo(HttpServletRequest request) {
        authorize(request);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("sessionStore", sessionStore.shared() ? "redis" : "memory");
        info.put("eventBus", eventBus.distributed() ? "redis" : "local");
        info.put("redisEnabled", redisProperties.isEnabled());
        info.put("keyPrefix", redisProperties.getKeyPrefix());
        info.put("redisReachable", pingRedis());
        info.put("probeLeader", probeLeader.isLeader());
        return info;
    }

    /** 控制面写入资产/拓扑后通知数据面失效缓存 / 刷新路由。 */
    @PostMapping("/config-changed")
    public Map<String, Object> configChanged(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        authorize(request);
        String changeType = body == null ? null : str(body.get("changeType"));
        Integer serverId = body == null ? null : toInt(body.get("serverId"));
        Integer toolId = body == null ? null : toInt(body.get("toolId"));
        String op = body == null ? null : str(body.get("op"));
        Map<String, Object> result = new LinkedHashMap<>();
        if (changeType == null) {
            result.put("ok", false);
            result.put("error", "changeType 不能为空");
            return result;
        }
        switch (changeType) {
            case "tool":
                changePublisher.toolChanged(serverId, toolId, op);
                break;
            case "prompt":
                changePublisher.promptChanged(serverId);
                break;
            case "resource":
                changePublisher.resourceChanged(serverId);
                break;
            case "routes":
                changePublisher.routesChanged();
                break;
            default:
                result.put("ok", false);
                result.put("error", "未知 changeType: " + changeType);
                return result;
        }
        logger.info("[INTERNAL] 收到配置变更 changeType={}, serverId={}", changeType, serverId);
        result.put("ok", true);
        return result;
    }

    /** 管理台「测试连通性」：在数据面发起真实请求（上游出网、SSRF 校验都在这里）。 */
    @PostMapping("/tools/test-connect")
    public Map<String, Object> testConnect(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        authorize(request);
        if (body == null || body.get("tool") == null) {
            Map<String, Object> bad = new LinkedHashMap<>();
            bad.put("ok", false);
            bad.put("error", "tool 不能为空");
            return bad;
        }
        McpTool tool = JSON.parseObject(JSON.toJSONString(body.get("tool")), McpTool.class);
        String authorization = str(body.get("authorization"));
        return mcpService.testConnection(tool, authorization);
    }

    /** 全部 Server 健康视图（控制面健康页批量渲染） */
    @GetMapping("/health/servers")
    public java.util.List<Map<String, Object>> healthAll(HttpServletRequest request) {
        authorize(request);
        return healthService.healthOfAll(mcpServerMapper.selectAll());
    }

    /** 单个 Server 健康详情 */
    @GetMapping("/health/servers/{id}")
    public Map<String, Object> healthOne(@PathVariable Integer id, HttpServletRequest request) {
        authorize(request);
        McpServer server = mcpServerMapper.selectByPrimaryKey(id);
        if (server == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Server 不存在");
        }
        return healthService.healthOf(server);
    }

    /** 立即探测（跳过缓存） */
    @PostMapping("/health/servers/{id}/probe")
    public Map<String, Object> healthProbe(@PathVariable Integer id, HttpServletRequest request) {
        authorize(request);
        McpServer server = mcpServerMapper.selectByPrimaryKey(id);
        if (server == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Server 不存在");
        }
        return healthService.probeNow(server);
    }

    /** 重建某个 Server 的工具向量索引（embedding 凭证只在数据面） */
    @PostMapping("/embeddings/reindex/{serverId}")
    public Map<String, Object> reindexEmbeddings(@PathVariable Integer serverId, HttpServletRequest request) {
        authorize(request);
        Map<String, Object> result = new LinkedHashMap<>();
        if (toolEmbeddingService == null) {
            result.put("enabled", false);
            result.put("message", "embedding 服务未初始化");
            return result;
        }
        Map<String, Object> stats = toolEmbeddingService.reindexServerSync(serverId);
        return stats;
    }

    /** 向量索引状态 */
    @GetMapping("/embeddings/stats")
    public Map<String, Object> embeddingStats(HttpServletRequest request) {
        authorize(request);
        if (toolEmbeddingService == null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("enabled", false);
            return result;
        }
        return toolEmbeddingService.stats();
    }

    /** 自检：Redis 是否真的可用（enabled=true 但连不上时，多副本语义不成立） */
    private boolean pingRedis() {
        if (!redisProperties.isEnabled()) {
            return false;
        }
        try {
            return "PONG".equalsIgnoreCase(redisSupport.commands().ping());
        } catch (Exception e) {
            return false;
        }
    }

    private void authorize(HttpServletRequest request) {
        if (internalToken != null && !internalToken.trim().isEmpty()) {
            String provided = request.getHeader("X-Internal-Token");
            if (!internalToken.equals(provided)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "内部接口凭证不正确");
            }
            return;
        }
        String remote = request.getRemoteAddr();
        boolean loopback = "127.0.0.1".equals(remote) || "::1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote);
        if (!loopback) {
            logger.warn("[INTERNAL] 未配置 mcp.internal.token，拒绝非回环访问 remote={}", remote);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "内部接口未配置凭证");
        }
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer toInt(Object value) {
        if (value == null || "".equals(value)) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
