package com.mcp.transfer.admin.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 登录失败限流（进程内实现）。
 *
 * <p>规则：同一「用户名 + 客户端 IP」在 15 分钟内失败 5 次，锁定 15 分钟。
 * 多实例部署时每个实例独立计数，生产可替换为 Redis 实现。
 */
@Component
public class LoginAttemptService {

    private static final int MAX_FAILURES = 5;
    private static final long WINDOW_MS = 15 * 60 * 1000L;
    private static final long LOCK_MS = 15 * 60 * 1000L;
    private static final int MAX_ENTRIES = 10_000;

    private static final class Attempt {
        final AtomicInteger failures = new AtomicInteger();
        final AtomicLong firstFailureAt = new AtomicLong();
        final AtomicLong lockedUntil = new AtomicLong();
    }

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    private String key(String username, String ip) {
        return (username == null ? "" : username) + "|" + (ip == null ? "" : ip);
    }

    /** 是否处于锁定状态 */
    public boolean isBlocked(String username, String ip) {
        Attempt a = attempts.get(key(username, ip));
        if (a == null) {
            return false;
        }
        long locked = a.lockedUntil.get();
        if (locked > 0 && locked > System.currentTimeMillis()) {
            return true;
        }
        if (locked > 0) {
            attempts.remove(key(username, ip));
        }
        return false;
    }

    /** 记录一次失败；达到阈值则锁定 */
    public void onFailure(String username, String ip) {
        cleanupIfNeeded();
        Attempt a = attempts.computeIfAbsent(key(username, ip), k -> new Attempt());
        long now = System.currentTimeMillis();
        long first = a.firstFailureAt.get();
        if (first == 0 || now - first > WINDOW_MS) {
            a.firstFailureAt.set(now);
            a.failures.set(0);
        }
        int count = a.failures.incrementAndGet();
        if (count >= MAX_FAILURES) {
            a.lockedUntil.set(now + LOCK_MS);
        }
    }

    /** 登录成功：清空计数 */
    public void onSuccess(String username, String ip) {
        attempts.remove(key(username, ip));
    }

    private void cleanupIfNeeded() {
        if (attempts.size() <= MAX_ENTRIES) {
            return;
        }
        long now = System.currentTimeMillis();
        attempts.entrySet().removeIf(e -> {
            Attempt a = e.getValue();
            long locked = a.lockedUntil.get();
            long first = a.firstFailureAt.get();
            return (locked > 0 && locked <= now) || (locked == 0 && now - first > WINDOW_MS);
        });
    }
}
