package com.mcp.transfer.admin.config;

import com.mcp.transfer.admin.security.AdminAuthInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 控制面 WebMvc 配置：后台 JWT + RBAC。
 *
 * <p>数据面的 MCP 拦截器（用户 token / 会话）不在这里注册；拆服务后控制面不加载它们。
 * 全局 CORS 由共享层的 {@code config.WebCorsConfig} 提供。
 */
@Configuration
public class AdminWebConfig implements WebMvcConfigurer {

    @Autowired
    private AdminAuthInterceptor adminAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/error");
    }
}
