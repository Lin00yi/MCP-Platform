package com.mcp.transfer.config.filter;

import com.mcp.transfer.identity.McpIdentityContext;
import com.mcp.transfer.shared.utils.TraceIdUtils;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 请求级 ThreadLocal 清理：无论请求成功、失败还是抛异常，结束时都清掉
 * {@link McpIdentityContext}（含原始用户 token 的缓存）与 traceId。
 *
 * <p>背景：SSE 入口（Endpoint / discovery 的 GET 长连接）在认证后直接返回 {@code SseEmitter}，
 * 控制器没有 finally 清理，身份与 token 会残留在 Tomcat 工作线程上，直到该线程被复用。
 * 在 Filter 层统一兜底比逐个拦截器/控制器补 finally 更可靠（Interceptor 的 afterCompletion
 * 对 async 请求是在异步完成时回调，线程不一定是设置 ThreadLocal 的那个线程）。
 *
 * <p>放在最外层（HIGHEST_PRECEDENCE）：保证内层任何 Filter/拦截器抛出的异常也能被清理。
 * 清理发生在请求的同步派发结束时，异步回调（SSE 推送/配置变更广播）不依赖该 ThreadLocal。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class McpThreadLocalCleanupFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            chain.doFilter(request, response);
        } finally {
            McpIdentityContext.clear();
            TraceIdUtils.clear();
        }
    }
}
