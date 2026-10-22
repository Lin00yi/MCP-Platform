package com.mcp.transfer.config.session;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP Streamable HTTP 的 SSE 连接注册表。
 * 按 sessionId 保存 GET 打开的 SseEmitter，并记录 session → server 归属。
 *
 * <p>增加最大连接数限制与停机清理，避免匿名连接耗尽资源。
 */
@Slf4j
@Component
public class SseEmitterRegistry {

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final Map<String, Integer> sessionServerMap = new ConcurrentHashMap<>();

    @Value("${mcp.sse.max-connections:1000}")
    private int maxConnections;

    /**
     * 尝试注册一个 SSE 连接。
     *
     * @return false 表示已达上限，调用方应返回 429/503
     */
    public boolean tryRegister(String sessionId, Integer serverId, SseEmitter emitter) {
        if (emitters.size() >= maxConnections) {
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

    private void remove(String sessionId) {
        emitters.remove(sessionId);
        sessionServerMap.remove(sessionId);
        log.info("SSE 连接移除，sessionId={}", sessionId);
    }

    /** 关闭并移除指定 session 的 SSE 连接 */
    public void close(String sessionId) {
        SseEmitter emitter = emitters.remove(sessionId);
        sessionServerMap.remove(sessionId);
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

    public int size() {
        return emitters.size();
    }

    @PreDestroy
    public void shutdown() {
        emitters.forEach((id, emitter) -> {
            try {
                emitter.complete();
            } catch (Exception ignore) {
                // ignore
            }
        });
        emitters.clear();
        sessionServerMap.clear();
    }
}
