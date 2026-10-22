package com.mcp.transfer.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 下游调用熔断（E5 的轻量实现）。
 *
 * <p>与健康探测互补：探测用于展示与排障，熔断保护真实调用链——
 * 连续失败达到阈值后短路一段时间，避免每轮对话都等待超时。
 *
 * <ul>
 *   <li>CLOSED：正常放行；连续失败达到阈值 → OPEN；</li>
 *   <li>OPEN：直接拒绝，不发起下游请求；到期后放行一次探测；</li>
 *   <li>HALF_OPEN：探测成功 → CLOSED；失败 → 重新 OPEN。</li>
 * </ul>
 *
 * <p>状态为进程内，按 Server 维度；多副本各自独立，可接受。
 */
@Component
public class UpstreamCircuitBreaker {

    private static final Logger logger = LoggerFactory.getLogger(UpstreamCircuitBreaker.class);

    @Value("${mcp.health.circuit.failure-threshold:5}")
    private int failureThreshold;

    @Value("${mcp.health.circuit.open-seconds:30}")
    private int openSeconds;

    private final Map<Integer, State> states = new ConcurrentHashMap<>();

    public boolean allow(Integer serverId) {
        if (serverId == null || failureThreshold <= 0) {
            return true;
        }
        State state = states.get(serverId);
        if (state == null) {
            return true;
        }
        synchronized (state) {
            if (state.openUntil == 0) {
                return true;
            }
            if (System.currentTimeMillis() >= state.openUntil) {
                // 到期：放行一次半开探测
                state.openUntil = 0;
                state.halfOpen = true;
                logger.info("[CIRCUIT] serverId={} 进入半开，放行一次探测", serverId);
                return true;
            }
            return false;
        }
    }

    public void recordSuccess(Integer serverId) {
        if (serverId == null) {
            return;
        }
        State state = states.get(serverId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.halfOpen || state.openUntil > 0) {
                logger.info("[CIRCUIT] serverId={} 探测成功，熔断关闭", serverId);
            }
            state.failures = 0;
            state.halfOpen = false;
            state.openUntil = 0;
        }
    }

    public void recordFailure(Integer serverId) {
        if (serverId == null || failureThreshold <= 0) {
            return;
        }
        State state = states.computeIfAbsent(serverId, key -> new State());
        synchronized (state) {
            if (state.halfOpen) {
                state.halfOpen = false;
                open(serverId, state, "半开探测失败");
                return;
            }
            state.failures++;
            if (state.failures >= failureThreshold) {
                open(serverId, state, "连续失败 " + state.failures + " 次");
            }
        }
    }

    /** 熔断原因（用于工具级 isError 文案）。 */
    public String reason(Integer serverId) {
        State state = serverId == null ? null : states.get(serverId);
        long remaining = state == null || state.openUntil == 0
                ? 0 : Math.max(1, (state.openUntil - System.currentTimeMillis()) / 1000);
        return "上游服务暂时不可用（已熔断），约 " + remaining + " 秒后自动重试；"
                + "请稍后再试或联系管理员。";
    }

    public boolean isOpen(Integer serverId) {
        State state = serverId == null ? null : states.get(serverId);
        return state != null && state.openUntil > 0
                && System.currentTimeMillis() < state.openUntil;
    }

    /** 管理端/排障用：手动恢复。 */
    public void reset(Integer serverId) {
        if (serverId != null) {
            states.remove(serverId);
        }
    }

    private void open(Integer serverId, State state, String cause) {
        state.openUntil = System.currentTimeMillis() + Math.max(1, openSeconds) * 1000L;
        state.failures = 0;
        logger.warn("[CIRCUIT] serverId={} 熔断打开（{}），{} 秒内短路", serverId, cause, openSeconds);
    }

    private static final class State {
        private int failures;
        private long openUntil;
        private boolean halfOpen;
    }
}
