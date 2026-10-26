package com.mcp.transfer.admin.security;

import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import com.mcp.transfer.admin.security.AdminAuthContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AdminJwtUtilTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @BeforeAll
    static void setUp() {
        // 在 AdminJwtUtil 类首次加载前注入测试密钥
        System.setProperty("mcp.admin.jwt-secret", SECRET);
    }

    @Test
    void signAndParseRoundTrip() {
        Date updateTime = new Date(1_700_000_000_000L);
        String token = AdminJwtUtil.sign(1L, "alice", "ADMIN", updateTime);
        AdminAuthContext ctx = AdminJwtUtil.parse(token);
        assertNotNull(ctx);
        assertEquals(1L, ctx.getUserId());
        assertEquals("alice", ctx.getUsername());
        assertEquals("ADMIN", ctx.getRole());
        assertEquals(updateTime.getTime(), ctx.getUserUpdatedAt());
    }

    @Test
    void rejectsTokenWithoutExp() {
        String token = JWT.create()
                .setPayload("userId", 1L)
                .setPayload("role", "ADMIN")
                .setKey(SECRET.getBytes(StandardCharsets.UTF_8))
                .sign();
        assertNull(AdminJwtUtil.parse(token));
    }

    @Test
    void rejectsExpiredToken() {
        String token = JWT.create()
                .setPayload("userId", 1L)
                .setPayload("role", "ADMIN")
                .setIssuedAt(new Date(System.currentTimeMillis() - 100_000L))
                .setExpiresAt(new Date(System.currentTimeMillis() - 10_000L))
                .setKey(SECRET.getBytes(StandardCharsets.UTF_8))
                .sign();
        assertNull(AdminJwtUtil.parse(token));
    }

    @Test
    void rejectsTamperedToken() {
        String token = AdminJwtUtil.sign(1L, "alice", "ADMIN", new Date());
        assertNull(AdminJwtUtil.parse(token + "x"));
    }

    @Test
    void rejectsTokenSignedWithWrongKey() {
        String token = JWT.create()
                .setPayload("userId", 1L)
                .setPayload("role", "SUPER_ADMIN")
                .setExpiresAt(new Date(System.currentTimeMillis() + 60_000L))
                .setKey("another-secret-another-secret-1234".getBytes(StandardCharsets.UTF_8))
                .sign();
        assertNull(AdminJwtUtil.parse(token));
    }

    /** 登录有效期可配置：token 的 exp - iat 等于配置值 */
    @Test
    void ttlIsConfigurable() {
        try {
            AdminJwtUtil.setTtlHours(24);
            String token = AdminJwtUtil.sign(1L, "alice", "ADMIN", new Date());
            JWT jwt = JWTUtil.parseToken(token);
            long iat = Long.parseLong(jwt.getPayload("iat").toString());
            long exp = Long.parseLong(jwt.getPayload("exp").toString());
            assertEquals(24 * 60 * 60L, exp - iat);
        } finally {
            AdminJwtUtil.setTtlHours(AdminJwtUtil.DEFAULT_TTL_MS / (60L * 60L * 1000L));
        }
    }

    /** 最长 7 天：超过 168 小时按 168 小时处理，低于 1 小时按 1 小时处理 */
    @Test
    void ttlIsClampedToSevenDays() {
        try {
            AdminJwtUtil.setTtlHours(9999);
            assertEquals(AdminJwtUtil.MAX_TTL_MS, AdminJwtUtil.getTtlMillis());
            assertEquals(AdminJwtUtil.MAX_TTL_MS, 7L * 24 * 60 * 60 * 1000L);

            AdminJwtUtil.setTtlHours(0);
            assertEquals(AdminJwtUtil.MIN_TTL_MS, AdminJwtUtil.getTtlMillis());
        } finally {
            AdminJwtUtil.setTtlHours(AdminJwtUtil.DEFAULT_TTL_MS / (60L * 60L * 1000L));
        }
    }
}
