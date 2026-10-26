package com.mcp.transfer.admin.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 登录失败限流（进程内实现）。
 *
 * <p>双维度计数（任一维度达阈值即锁定，锁定 15 分钟）：
 * <ul>
 *   <li><b>用户名维度</b>：同一账号 15 分钟内失败 20 次（{@code mcp.login.max-failures-per-user}）→ 锁定。
 *       用于抵御「换 IP 轮询爆破同一账号」（伪造/轮换 X-Forwarded-For 之前可完全绕过）；</li>
 *   <li><b>IP 维度</b>：同一来源 IP 15 分钟内失败 5 次（{@code mcp.login.max-failures-per-ip}）→ 锁定。
 *       用于抵御「同一 IP 撞库多个账号」。</li>
 * </ul>
 * 登录成功清空该用户名与 IP 的计数。
 *
 * <p>客户端 IP 必须来自可信来源（见 {@code IpUtils.clientIp}：优先反向代理覆写的 X-Real-IP，
 * 否则 socket 地址；不再信任可伪造的 X-Forwarded-For 首段）。
 *
 * <p>多实例部署时每个实例独立计数，生产可替换为 Redis 实现（待办 P2）。
 */
@Component
public class LoginAttemptService {

    private static final Logger logger = LoggerFactory.getLogger(LoginAttemptService.class);

    /** 用户名维度：15 分钟内失败 20 次锁定（比 IP 维度宽松，避免误伤共享出口） */
    @Value("${mcp.login.max-failures-per-user:20}")
    private int userMaxFailures = 20;
    /** IP 维度：15 分钟内失败 5 次锁定 */
    @Value("${mcp.login.max-failures-per-ip:5}")
    private int ipMaxFailures = 5;

    private static final long WINDOW_MS = 15 * 60 * 1000L;
    private static final long LOCK_MS = 15 * 60 * 1000L;
    private static final int MAX_ENTRIES = 20_000;

    private static final class Attempt {
        final AtomicInteger failures = new AtomicInteger();
        final AtomicLong firstFailureAt = new AtomicLong();
        final AtomicLong lockedUntil = new AtomicLong();
    }

    /** 用户名维度计数：key = 规范化用户名 */
    private final Map<String, Attempt> userAttempts = new ConcurrentHashMap<>();

    /** IP 维度计数：key = 客户端 IP */
    private final Map<String, Attempt> ipAttempts = new ConcurrentHashMap<>();

    /** 是否处于锁定状态（用户名维度或 IP 维度任一命中） */
    public boolean isBlocked(String username, String ip) {
        return isLocked(userAttempts, userKey(username)) || isLocked(ipAttempts, ipKey(ip));
    }

    /** 记录一次失败；用户名/IP 两个维度分别累计，达到各自阈值则锁定 */
    public void onFailure(String username, String ip) {
        cleanupIfNeeded(userAttempts);
        cleanupIfNeeded(ipAttempts);
        long now = System.currentTimeMillis();
        int userCount = incr(userAttempts, userKey(username), now, userMaxFailures);
        int ipCount = incr(ipAttempts, ipKey(ip), now, ipMaxFailures);
        if (userCount == userMaxFailures) {
            logger.warn("[LOGIN_LIMIT] 用户名维度触发锁定 username={}, count={}（{} 分钟）",
                    mask(username), userCount, LOCK_MS / 60_000L);
        }
        if (ipCount == ipMaxFailures) {
            logger.warn("[LOGIN_LIMIT] IP 维度触发锁定 ip={}, count={}（{} 分钟）",
                    ip, ipCount, LOCK_MS / 60_000L);
        }
    }

    /** 登录成功：清空该用户名与 IP 的计数 */
    public void onSuccess(String username, String ip) {
        userAttempts.remove(userKey(username));
        ipAttempts.remove(ipKey(ip));
    }

    private boolean isLocked(Map<String, Attempt> attempts, String key) {
        Attempt a = attempts.get(key);
        if (a == null) {
            return false;
        }
        long locked = a.lockedUntil.get();
        if (locked > 0 && locked > System.currentTimeMillis()) {
            return true;
        }
        if (locked > 0) {
            attempts.remove(key);
        }
        return false;
    }

    /** 累计失败次数（窗口外重新计数），返回当前窗口内失败次数 */
    private int incr(Map<String, Attempt> attempts, String key, long now, int threshold) {
        Attempt a = attempts.computeIfAbsent(key, k -> new Attempt());
        long first = a.firstFailureAt.get();
        if (first == 0 || now - first > WINDOW_MS) {
            a.firstFailureAt.set(now);
            a.failures.set(0);
        }
        int count = a.failures.incrementAndGet();
        if (threshold > 0 && count >= threshold) {
            a.lockedUntil.set(now + LOCK_MS);
        }
        return count;
    }

    private void cleanupIfNeeded(Map<String, Attempt> attempts) {
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

    private String userKey(String username) {
        return username == null ? "" : username.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String ipKey(String ip) {
        return ip == null || ip.isBlank() ? "unknown" : ip.trim();
    }

    /** 日志脱敏：用户名保留首尾各 1 字符 */
    private String mask(String username) {
        if (username == null || username.length() <= 2) {
            return "***";
        }
        return username.charAt(0) + "***" + username.charAt(username.length() - 1);
    }
}
