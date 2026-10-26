package com.mcp.transfer.admin.config;

import com.mcp.transfer.admin.security.AdminJwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;

/**
 * 把 Spring 配置中的 {@code mcp.admin.jwt-secret} / {@code mcp.admin.jwt-ttl-hours}
 * 注入静态工具类 {@link AdminJwtUtil}，保证无论通过环境变量、启动参数还是 yml 配置，
 * 签名/验签与登录有效期使用同一份配置。
 */
@Component
public class AdminJwtConfig {

    private static final Logger log = LoggerFactory.getLogger(AdminJwtConfig.class);

    @Value("${mcp.admin.jwt-secret:}")
    private String adminJwtSecret;

    /** 登录有效期（小时）：默认 7 天，支持 1～168（最长 7 天），超过上限按上限处理 */
    @Value("${mcp.admin.jwt-ttl-hours:168}")
    private String adminJwtTtlHours;

    @PostConstruct
    public void init() {
        if (StringUtils.hasText(adminJwtSecret)) {
            AdminJwtUtil.setSecret(adminJwtSecret);
        }
        applyTtlHours(adminJwtTtlHours);
    }

    private void applyTtlHours(String raw) {
        if (!StringUtils.hasText(raw)) {
            AdminJwtUtil.setTtlHours(AdminJwtUtil.DEFAULT_TTL_MS / (60L * 60L * 1000L));
            return;
        }
        try {
            long hours = Long.parseLong(raw.trim());
            AdminJwtUtil.setTtlHours(hours);
            log.info("[ADMIN_JWT] 后台登录有效期 {} 小时（上限 168 小时 / 7 天）",
                    AdminJwtUtil.getTtlMillis() / (60L * 60L * 1000L));
        } catch (NumberFormatException e) {
            log.warn("[ADMIN_JWT] mcp.admin.jwt-ttl-hours 不是整数：{}，按默认 168 小时处理", raw);
            AdminJwtUtil.setTtlHours(AdminJwtUtil.DEFAULT_TTL_MS / (60L * 60L * 1000L));
        }
    }
}
