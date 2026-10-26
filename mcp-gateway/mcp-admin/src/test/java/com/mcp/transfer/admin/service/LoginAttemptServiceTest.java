package com.mcp.transfer.admin.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录失败限流：IP 维度 5 次/15min，用户名维度 20 次/15min（防止换 IP 爆破同一账号）。
 */
class LoginAttemptServiceTest {

    @Test
    void ipDimensionLocksAfterFiveFailures() {
        LoginAttemptService service = new LoginAttemptService();
        for (int i = 0; i < 5; i++) {
            service.onFailure("alice", "203.0.113.7");
        }
        // 同一 IP 换用户名也被锁（IP 维度）
        assertTrue(service.isBlocked("bob", "203.0.113.7"));
        // 其他 IP 不受影响
        assertFalse(service.isBlocked("alice", "198.51.100.1"));
    }

    @Test
    void usernameDimensionLocksEvenWhenIpChanges() {
        LoginAttemptService service = new LoginAttemptService();
        for (int i = 0; i < 20; i++) {
            // 每次换 IP，模拟伪造/轮换来源 IP 的爆破
            service.onFailure("admin", "203.0.113." + (i % 250));
        }
        assertTrue(service.isBlocked("admin", "198.51.100.99"));
    }

    @Test
    void successClearsBothDimensions() {
        LoginAttemptService service = new LoginAttemptService();
        for (int i = 0; i < 4; i++) {
            service.onFailure("alice", "203.0.113.7");
        }
        service.onSuccess("alice", "203.0.113.7");
        assertFalse(service.isBlocked("alice", "203.0.113.7"));
        // 清空后重新计数：再失败 4 次仍不该锁
        for (int i = 0; i < 4; i++) {
            service.onFailure("alice", "203.0.113.7");
        }
        assertFalse(service.isBlocked("alice", "203.0.113.7"));
    }

    @Test
    void ipDimensionCountsAcrossUsernamesButNotBeforeThreshold() {
        LoginAttemptService service = new LoginAttemptService();
        for (int i = 0; i < 4; i++) {
            service.onFailure("user" + i, "203.0.113.7");
        }
        assertFalse(service.isBlocked("another", "203.0.113.7"));
        service.onFailure("user9", "203.0.113.7");
        assertTrue(service.isBlocked("another", "203.0.113.7"));
    }
}
