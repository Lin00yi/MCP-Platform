package com.mcp.transfer.admin.security;

import cn.hutool.jwt.JWT;
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
}
