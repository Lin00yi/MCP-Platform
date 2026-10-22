package com.mcp.transfer.config.filter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 数据面 WebMvc 配置：MCP 协议鉴权 + 会话 + 发现鉴权，以及请求体可重复读的包装。
 *
 * <p>控制面的后台 JWT 拦截器已迁到 {@code admin.config.AdminWebConfig}；
 * 拆服务后两边各自只注册自己那套拦截器，MCP 请求不再需要「先判断属于哪一面」。
 */
@Configuration
public class McpWebConfig implements WebMvcConfigurer, Filter {

    @Autowired
    private McpSessionInterceptor mcpSessionInterceptor;

    @Autowired
    private McpAuthInterceptor mcpAuthInterceptor;

    @Autowired
    private McpDiscoveryAuthInterceptor mcpDiscoveryAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // MCP 协议鉴权（只认 x-dsh-auth-token）
        // /internal/** 是控制面专用接口，由 InternalApiController 自己做 X-Internal-Token 校验
        registry.addInterceptor(mcpAuthInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/error", "/internal/**");
        // 客户端发现接口同样只认用户 token
        registry.addInterceptor(mcpDiscoveryAuthInterceptor)
                .addPathPatterns("/mcp/discovery/**");
        // 会话校验 / Streamable HTTP 的 Mcp-Session-Id
        registry.addInterceptor(mcpSessionInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/error", "/internal/**");
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
