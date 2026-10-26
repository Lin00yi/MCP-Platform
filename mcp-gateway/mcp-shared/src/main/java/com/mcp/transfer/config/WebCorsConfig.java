package com.mcp.transfer.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局 CORS：MCP 客户端（浏览器形态的 Inspector / 自研前端）会发 OPTIONS 预检，
 * 不放开会得到 405。
 *
 * <p>两个服务（数据面 / 控制面）都需要，因此放在共享层，避免各自重复实现。
 * 生产建议通过 {@code MCP_CORS_ALLOWED_ORIGINS} 收敛为控制台域名（逗号分隔）；
 * 默认 {@code *} 保持本地开发与 MCP Inspector 可用。凭据走 Header（非 Cookie），
 * 因此不使用 allowCredentials。
 */
@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

    /** 允许的来源，逗号分隔；{@code *} 表示不限制 */
    @Value("${mcp.cors.allowed-origins:*}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String raw = allowedOrigins == null ? "" : allowedOrigins.trim();
        String[] origins;
        if (!StringUtils.hasText(raw) || "*".equals(raw)) {
            origins = new String[]{"*"};
        } else {
            origins = raw.split(",");
            for (int i = 0; i < origins.length; i++) {
                origins[i] = origins[i].trim();
            }
        }
        registry.addMapping("/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
