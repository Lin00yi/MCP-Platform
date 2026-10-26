package com.mcp.transfer.config.session;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * MCP Streamable HTTP 的 SSE 连接注册表。
 * 按 sessionId 保存 GET 打开的 SseEmitter，并记录 session → server 归属。
 *
 * <p>资源控制：
 * <ul>
 *   <li>全局最大连接数（{@code mcp.sse.max-connections}）；</li>
 *   <li>单用户最大连接数（{@code mcp.sse.max-connections-per-user}，默认 20），
 *       避免单个客户端占满全局配额；</li>
 *   <li>每 {@code mcp.sse.ping-interval-ms}（默认 25s）发送一次 SSE 注释心跳，
 *       让中间层/客户端及时感知断链并触发清理，而不是等 30 分钟超时；</li>
 *   <li>停机清理全部连接。</li>
 * </ul>
 */
@Slf4j
@Component
public class SseEmitterRegistry {

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final Map<String, Integer> sessionServerMap = new ConcurrentHashMap<>();
    /** sessionId → userId（匿名/未知为 null，不写入） */
    private final Map<String, String> sessionUserMap = new ConcurrentHashMap<>();

    @Value("${mcp.sse.max-connections:1000}")
    private int maxConnections;

    /** 单用户连接配额；<=0 表示不限制 */
    @Value("${mcp.sse.max-connections-per-user:20}")
    private int maxConnectionsPerUser;

    /** 心跳间隔（毫秒），<=0 表示关闭心跳 */
    @Value("${mcp.sse.ping-interval-ms:25000}")
    private long pingIntervalMs;

    private ScheduledExecutorService pingScheduler;

    @PostConstruct
    public void startHeartbeat() {
        if (pingIntervalMs <= 0) {
            log.info("[SSE] 心跳已关闭（mcp.sse.ping-interval-ms<=0）");
            return;
        }
        pingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        pingScheduler.scheduleAtFixedRate(this::pingAll, pingIntervalMs, pingIntervalMs, TimeUnit.MILLISECONDS);
        log.info("[SSE] 心跳已启用：interval={}ms，maxConnections={}，perUser={}",
                pingIntervalMs, maxConnections, maxConnectionsPerUser);
    }

    /**
     * 尝试注册一个 SSE 连接（不带用户维度，兼容旧调用）。
     *
     * @return false 表示已达全局/单用户上限，调用方应返回 429/503
     */
    public boolean tryRegister(String sessionId, Integer serverId, SseEmitter emitter) {
        return tryRegister(sessionId, serverId, emitter, null);
    }

    /**
     * 尝试注册一个 SSE 连接。
     *
     * @param userId 当前登录用户（用于单用户配额）；可为 null
     * @return false 表示已达上限，调用方应返回 429/503
     */
    public boolean tryRegister(String sessionId, Integer serverId, SseEmitter emitter, String userId) {
        if (emitters.size() >= maxConnections) {
            return false;
        }
        if (userId != null && maxConnectionsPerUser > 0
                && countUserConnections(userId, sessionId) >= maxConnectionsPerUser) {
            log.warn("[SSE] 单用户连接数已达上限 userId={}，拒绝新连接 sessionId={}", userId, sessionId);
            return false;
        }
        SseEmitter old = emitters.put(sessionId, emitter);
        if (old != null) {
            try {
                old.complete();
            } catch (Exception ignore) {
                // ignore
            }
        }
        if (serverId != null) {
            sessionServerMap.put(sessionId, serverId);
        }
        if (userId != null) {
            sessionUserMap.put(sessionId, userId);
        } else {
            sessionUserMap.remove(sessionId);
        }
        emitter.onCompletion(() -> remove(sessionId));
        emitter.onTimeout(() -> remove(sessionId));
        emitter.onError(e -> remove(sessionId));
        return true;
    }

    /** 兼容旧调用 */
    public void register(String sessionId, Integer serverId, SseEmitter emitter) {
        if (!tryRegister(sessionId, serverId, emitter)) {
            throw new IllegalStateException("SSE 连接数已达上限: " + maxConnections);
        }
    }

    /** 某用户当前连接数（excludeSessionId 可用于「替换同一 session」时排除自身） */
    public int countUserConnections(String userId, String excludeSessionId) {
        if (userId == null) {
            return 0;
        }
        int count = 0;
        for (Map.Entry<String, String> entry : sessionUserMap.entrySet()) {
            if (userId.equals(entry.getValue()) && !entry.getKey().equals(excludeSessionId)) {
                count++;
            }
        }
        return count;
    }

    private void remove(String sessionId) {
        emitters.remove(sessionId);
        sessionServerMap.remove(sessionId);
        sessionUserMap.remove(sessionId);
        log.info("SSE 连接移除，sessionId={}", sessionId);
    }

    /** 关闭并移除指定 session 的 SSE 连接 */
    public void close(String sessionId) {
        SseEmitter emitter = emitters.remove(sessionId);
        sessionServerMap.remove(sessionId);
        sessionUserMap.remove(sessionId);
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception ignore) {
                // ignore
            }
        }
    }

    /** 向某 server 的所有 SSE 连接广播一条 JSON-RPC 通知 */
    public void broadcastToServer(Integer serverId, String jsonMessage) {
        if (serverId == null || jsonMessage == null) {
            return;
        }
        emitters.forEach((sessionId, emitter) -> {
            if (serverId.equals(sessionServerMap.get(sessionId))) {
                try {
                    emitter.send(SseEmitter.event().data(jsonMessage));
                } catch (Exception e) {
                    log.warn("SSE 广播失败, sessionId={}, serverId={}", sessionId, serverId, e);
                    remove(sessionId);
                }
            }
        });
    }

    /**
     * 向未绑定具体 Server 的 SSE 会话广播（统一聚合入口）。
     * 聚合入口的会话在注册时 serverId 为 null，不能用 broadcastToServer 命中。
     */
    public void broadcastToAggregate(String jsonMessage) {
        if (jsonMessage == null) {
            return;
        }
        emitters.forEach((sessionId, emitter) -> {
            if (sessionServerMap.get(sessionId) != null) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().data(jsonMessage));
            } catch (Exception e) {
                log.warn("聚合入口 SSE 广播失败, sessionId={}", sessionId, e);
                remove(sessionId);
            }
        });
    }

    /** 心跳：给所有连接发 SSE 注释；失败即视为断链并移除（不抛异常） */
    void pingAll() {
        if (emitters.isEmpty()) {
            return;
        }
        emitters.forEach((sessionId, emitter) -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception e) {
                log.info("[SSE] 心跳失败，移除连接 sessionId={}: {}", sessionId, e.getMessage());
                remove(sessionId);
            }
        });
    }

    public int size() {
        return emitters.size();
    }

    @PreDestroy
    public void shutdown() {
        if (pingScheduler != null) {
            pingScheduler.shutdownNow();
        }
        emitters.forEach((id, emitter) -> {
            try {
                emitter.complete();
            } catch (Exception ignore) {
                // ignore
            }
        });
        emitters.clear();
        sessionServerMap.clear();
        sessionUserMap.clear();
    }
}
