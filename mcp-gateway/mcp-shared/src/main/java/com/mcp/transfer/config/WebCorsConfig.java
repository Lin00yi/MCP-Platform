package com.mcp.transfer.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局 CORS：MCP 客户端（浏览器形态的 Inspector / 自研前端）会发 OPTIONS 预检，
 * 不放开会得到 405。
 *
 * <p>两个服务（数据面 / 控制面）都需要，因此放在共享层，避免各自重复实现。
 */
@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
