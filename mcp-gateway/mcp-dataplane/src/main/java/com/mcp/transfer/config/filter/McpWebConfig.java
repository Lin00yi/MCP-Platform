package com.mcp.transfer.config.filter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 数据面 WebMvc 配置：会话 + 发现鉴权，以及请求体可重复读的包装。
 *
 * <p>对外只有 {@code /mcp/e/{endpointKey}} 与 {@code /mcp/discovery/**}；
 * 旧的 {@code /mcp/v1/{serverPath}} 动态路由已下线，Endpoint 入口在控制器内完成用户 token 认证。
 */
@Configuration
public class McpWebConfig implements WebMvcConfigurer, Filter {

    @Autowired
    private McpSessionInterceptor mcpSessionInterceptor;

    @Autowired
    private McpDiscoveryAuthInterceptor mcpDiscoveryAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 客户端发现接口只认用户 token
        registry.addInterceptor(mcpDiscoveryAuthInterceptor)
                .addPathPatterns("/mcp/discovery/**");
        // 会话校验 / Streamable HTTP 的 Mcp-Session-Id：只作用于 Endpoint 数据面，
        // 否则未注册路径也会被会话拦截器改成 400，无法正确返回 404
        registry.addInterceptor(mcpSessionInterceptor)
                .addPathPatterns("/mcp/e/**");
    }

    /**
     * 包装请求体，支持重复读取（鉴权拦截器读一次，控制器再读一次）。
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletResponse httpResponse = response instanceof HttpServletResponse ? (HttpServletResponse) response : null;
        try {
            ServletRequest wrappedRequest = request;
            if (request instanceof HttpServletRequest) {
                wrappedRequest = new McpRequestWrapper((HttpServletRequest) request);
            }
            chain.doFilter(wrappedRequest, response);
        } catch (McpRequestWrapper.PayloadTooLargeException e) {
            if (httpResponse != null) {
                httpResponse.setStatus(413);
                httpResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
                httpResponse.setCharacterEncoding("UTF-8");
                httpResponse.getWriter().write("{\"code\":413,\"message\":\"请求体过大\"}");
            }
        }
    }
}
