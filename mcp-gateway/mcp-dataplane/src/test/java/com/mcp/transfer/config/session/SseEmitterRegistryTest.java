package com.mcp.transfer.config.session;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSE 注册表：全局/单用户配额 + 心跳清理。
 */
class SseEmitterRegistryTest {

    private SseEmitterRegistry registry(int maxConnections, int perUser) {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        ReflectionTestUtils.setField(registry, "maxConnections", maxConnections);
        ReflectionTestUtils.setField(registry, "maxConnectionsPerUser", perUser);
        ReflectionTestUtils.setField(registry, "pingIntervalMs", 0L);
        registry.startHeartbeat();
        return registry;
    }

    @Test
    void perUserQuotaRejectsExtraConnections() {
        SseEmitterRegistry registry = registry(1000, 20);

        for (int i = 0; i < 20; i++) {
            assertTrue(registry.tryRegister("s" + i, null, new SseEmitter(), "u1"));
        }
        assertFalse(registry.tryRegister("s20", null, new SseEmitter(), "u1"));
        // 其他用户不受影响
        assertTrue(registry.tryRegister("other", null, new SseEmitter(), "u2"));
        assertEquals(20, registry.countUserConnections("u1", null));

        // 关闭一个后配额释放
        registry.close("s0");
        assertTrue(registry.tryRegister("s21", null, new SseEmitter(), "u1"));
        registry.shutdown();
    }

    @Test
    void globalQuotaStillApplies() {
        SseEmitterRegistry registry = registry(2, 100);
        assertTrue(registry.tryRegister("a", null, new SseEmitter(), "u1"));
        assertTrue(registry.tryRegister("b", null, new SseEmitter(), "u2"));
        assertFalse(registry.tryRegister("c", null, new SseEmitter(), "u3"));
        registry.shutdown();
    }

    @Test
    void registrationWithoutUserFallsBackToGlobalQuotaOnly() {
        SseEmitterRegistry registry = registry(1000, 1);
        assertTrue(registry.tryRegister("a", 7, new SseEmitter()));
        assertTrue(registry.tryRegister("b", 7, new SseEmitter()));
        assertEquals(2, registry.size());
        registry.shutdown();
    }

    @Test
    void heartbeatRemovesDeadEmitters() {
        SseEmitterRegistry registry = registry(1000, 20);
        assertTrue(registry.tryRegister("alive", null, new SseEmitter(), "u1"));
        assertTrue(registry.tryRegister("dead", null, new BrokenEmitter(), "u1"));

        registry.pingAll();

        assertEquals(1, registry.size());
        assertTrue(registry.countUserConnections("u1", null) == 1);
        registry.shutdown();
    }

    /** 模拟客户端已断开的 emitter：send 抛 IOException */
    private static final class BrokenEmitter extends SseEmitter {
        @Override
        public void send(SseEventBuilder builder) throws IOException {
            throw new IOException("client gone");
        }
    }
}
