package com.mcp.transfer.admin.security;

import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import cn.hutool.jwt.JWTValidator;
import cn.hutool.jwt.signers.JWTSignerUtil;
import com.mcp.transfer.admin.security.AdminAuthContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Date;

/**
 * 后台管理 JWT 工具（HS256）。
 *
 * <p>安全约束：
 * <ul>
 *   <li>生产环境必须通过 {@code MCP_ADMIN_JWT_SECRET} 或 {@code -Dmcp.admin.jwt-secret} 配置密钥，
 *       否则启动失败，避免使用源码里的开发默认值；</li>
 *   <li>token 必须包含 {@code exp}，无 {@code exp} 一律拒绝；</li>
 *   <li>token 内携带 {@code uat}（用户记录 update_time），用户被编辑/改密/禁用后旧 token 立即失效。</li>
 * </ul>
 */
public class AdminJwtUtil {

    private static final Logger log = LoggerFactory.getLogger(AdminJwtUtil.class);

    /** 开发默认口令（仅非 prod 环境兜底；生产必须显式配置） */
    private static final String DEV_SECRET = "mcp-gateway-dev-admin-jwt-2026-change-me";

    /** 签名密钥（可通过 MCP_ADMIN_JWT_SECRET 环境变量、-Dmcp.admin.jwt-secret 或 Spring 配置覆盖） */
    private static volatile String SECRET = resolveSecret();

    /**
     * 由 Spring 配置显式注入（支持 application-*.yml / 环境变量 / 启动参数），
     * 保证静态工具类与 Spring 环境使用同一份密钥。
     */
    public static void setSecret(String secret) {
        if (secret == null || secret.trim().isEmpty()) {
            return;
        }
        SECRET = validateSecret(secret.trim());
    }

    private static String resolveSecret() {
        String env = System.getenv("MCP_ADMIN_JWT_SECRET");
        if (env != null && !env.trim().isEmpty()) {
            return validateSecret(env.trim());
        }
        String prop = System.getProperty("mcp.admin.jwt-secret");
        if (prop != null && !prop.trim().isEmpty()) {
            return validateSecret(prop.trim());
        }
        if (isProdProfile()) {
            throw new IllegalStateException(
                    "生产环境必须配置 MCP_ADMIN_JWT_SECRET（或 -Dmcp.admin.jwt-secret），禁止使用默认密钥");
        }
        log.warn("[ADMIN_JWT] 未配置 MCP_ADMIN_JWT_SECRET，使用开发默认密钥（仅限本地/测试环境）");
        return DEV_SECRET;
    }

    private static String validateSecret(String secret) {
        if (secret.length() < 32) {
            throw new IllegalStateException("MCP_ADMIN_JWT_SECRET 长度必须 >= 32 字符");
        }
        return secret;
    }

    private static boolean isProdProfile() {
        String profiles = System.getenv("SPRING_PROFILES_ACTIVE");
        if (profiles == null || profiles.trim().isEmpty()) {
            profiles = System.getProperty("spring.profiles.active");
        }
        return profiles != null && profiles.toLowerCase().contains("prod");
    }

    /** 登录有效期默认值：7 天（168 小时） */
    public static final long DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000L;

    /** 登录有效期下限：1 小时，避免误配成几乎立即过期 */
    public static final long MIN_TTL_MS = 60L * 60 * 1000L;

    /** 登录有效期上限：7 天（168 小时），超过按上限处理 */
    public static final long MAX_TTL_MS = 7L * 24 * 60 * 60 * 1000L;

    /** 当前登录有效期（毫秒），由 {@code mcp.admin.jwt-ttl-hours} 注入 */
    private static volatile long ttlMillis = DEFAULT_TTL_MS;

    /**
     * 设置登录有效期（小时）。
     *
     * <p>支持 1～168 小时（最长 7 天）：低于 1 小时按 1 小时、高于 168 小时按 168 小时处理并告警，
     * 防止误配出超长期登录态。
     */
    public static void setTtlHours(long hours) {
        long clamped = Math.max(1L, Math.min(168L, hours));
        if (clamped != hours) {
            log.warn("[ADMIN_JWT] 登录有效期 {} 小时超出 [1, 168] 范围，按 {} 小时处理", hours, clamped);
        }
        ttlMillis = clamped * 60L * 60L * 1000L;
    }

    /** 当前登录有效期（毫秒） */
    public static long getTtlMillis() {
        return ttlMillis;
    }

    public static String sign(Long userId, String username, String role, Date userUpdateTime) {
        byte[] key = SECRET.getBytes(StandardCharsets.UTF_8);
        long now = System.currentTimeMillis();
        return JWT.create()
                .setPayload("userId", userId)
                .setPayload("username", username)
                .setPayload("role", role)
                // 用户记录版本：update_time 变化（改密/禁用/角色变更）后旧 token 失效
                .setPayload("uat", userUpdateTime == null ? 0L : userUpdateTime.getTime())
                .setIssuedAt(new Date(now))
                .setExpiresAt(new Date(now + ttlMillis))
                .setKey(key)
                .sign();
    }

    /**
     * 验签并解析，失败返回 null。
     */
    public static AdminAuthContext parse(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        try {
            JWT jwt = JWTUtil.parseToken(token);
            JWTValidator.of(token)
                    .validateAlgorithm(JWTSignerUtil.hs256(SECRET.getBytes(StandardCharsets.UTF_8)))
                    .validateDate();

            // Hutool 的 validateDate 在缺少 exp 时不会报错，这里强制要求 exp
            Object expObj = jwt.getPayload("exp");
            if (expObj == null) {
                return null;
            }
            long expSeconds = Long.parseLong(expObj.toString());
            if (expSeconds * 1000L <= System.currentTimeMillis()) {
                return null;
            }

            Object userIdObj = jwt.getPayload("userId");
            Object usernameObj = jwt.getPayload("username");
            Object roleObj = jwt.getPayload("role");
            if (userIdObj == null || roleObj == null) {
                return null;
            }
            AdminAuthContext ctx = new AdminAuthContext();
            ctx.setUserId(Long.valueOf(userIdObj.toString()));
            ctx.setUsername(usernameObj == null ? null : usernameObj.toString());
            ctx.setRole(roleObj.toString());
            ctx.setTenantIds(Collections.emptyList());
            Object uatObj = jwt.getPayload("uat");
            ctx.setUserUpdatedAt(uatObj == null ? null : Long.valueOf(uatObj.toString()));
            return ctx;
        } catch (Exception e) {
            return null;
        }
    }
}
