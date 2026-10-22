package com.mcp.transfer.config.filter;

import com.mcp.transfer.utils.IpUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 客户端发现接口鉴权：/mcp/discovery/** 必须携带有效 {@code x-dsh-auth-token}。
 *
 * <p>管理台预览走 /mcp/admin/discovery/**，由 AdminAuthInterceptor 保护，不受本拦截器影响。
 */
@Slf4j
@Component
public class McpDiscoveryAuthInterceptor implements HandlerInterceptor {

    /** 兼容旧引用；发现不再按 app 过滤 */
    @Deprecated
    public static final String DISCOVERY_APP_ID_ATTR = "discoveryAppId";

    public static final String DISCOVERY_USER_ID_ATTR = "discoveryUserId";

    @Autowired
    private UserTokenAuthenticator userTokenAuthenticator;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        if (!path.startsWith("/mcp/discovery")) {
            return true;
        }
        if (userTokenAuthenticator.authenticate(request, response) == null) {
            log.warn("[DISCOVERY_AUTH] 拒绝匿名/无效凭证访问 {}, ip={}", path, IpUtils.clientIp(request));
            return false;
        }
        request.setAttribute(DISCOVERY_USER_ID_ATTR, request.getAttribute(UserTokenAuthenticator.USER_ID_ATTR));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        userTokenAuthenticator.clear();
    }
}
