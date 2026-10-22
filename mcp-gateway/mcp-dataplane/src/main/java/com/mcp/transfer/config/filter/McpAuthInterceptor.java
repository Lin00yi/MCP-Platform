package com.mcp.transfer.config.filter;

import com.alibaba.fastjson.JSON;
import com.mcp.transfer.dto.mcp.req.McpRequest;
import com.mcp.transfer.entity.McpServer;
import com.mcp.transfer.mapper.McpServerMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * MCP 数据面鉴权拦截器：只认 {@code x-dsh-auth-token}。
 *
 * <p>规则：
 * <ol>
 *   <li>{@code ping} / {@code notifications/*} 匿名放行；</li>
 *   <li>其余 MCP 方法（含 {@code initialize}）必须携带有效用户 token；</li>
 *   <li>不再做 app → server/tool/prompt/resource 授权，资产可见性由启用状态与暴露模式决定；</li>
 *   <li>不再断言 tenant。</li>
 * </ol>
 */
@Slf4j
@Component
public class McpAuthInterceptor implements HandlerInterceptor {

    public static final String MCP_SERVER_ID_ATTR = "mcpServerId";
    public static final String MCP_USER_ID_ATTR = UserTokenAuthenticator.USER_ID_ATTR;
    public static final String MCP_CUSTOMER_ID_ATTR = UserTokenAuthenticator.CUSTOMER_ID_ATTR;
    public static final String MCP_IDENTITY_ATTR = UserTokenAuthenticator.IDENTITY_ATTR;

    @Autowired
    private UserTokenAuthenticator userTokenAuthenticator;

    @Autowired
    private McpServerMapper mcpServerMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String requestBody = readRequestBody(request);
        if (!StringUtils.hasText(requestBody)) {
            // GET(SSE) / DELETE 没有 JSON body，但同样是数据面入口：注册路径必须认证
            McpServer streamingServer = mcpServerMapper.selectOneByPath(request.getRequestURI());
            if (streamingServer != null && !"POST".equalsIgnoreCase(request.getMethod())) {
                if (userTokenAuthenticator.authenticate(request, response) == null) {
                    return false;
                }
                request.setAttribute(MCP_SERVER_ID_ATTR, streamingServer.getId());
            }
            return true;
        }
        McpRequest mcpRequest;
        try {
            mcpRequest = JSON.parseObject(requestBody, McpRequest.class);
        } catch (Exception e) {
            return true; // 非 JSON 请求交给 MVC 处理
        }
        String method = mcpRequest == null ? null : mcpRequest.getMethod();
        if (method == null) {
            return true;
        }

        // ping / notifications 匿名放行；initialize 需要认证
        if (isAnonymous(method)) {
            return true;
        }

        // 仅处理真正注册过的 MCP 端点；管理端/发现/Endpoint 等由各自入口处理
        McpServer server = mcpServerMapper.selectOneByPath(request.getRequestURI());
        if (server == null) {
            return true;
        }

        if (userTokenAuthenticator.authenticate(request, response) == null) {
            return false;
        }
        request.setAttribute(MCP_SERVER_ID_ATTR, server.getId());
        return true;
    }

    /** ping / notifications/* 不需要认证 */
    private boolean isAnonymous(String method) {
        return "ping".equals(method) || method.startsWith("notifications/");
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        userTokenAuthenticator.clear();
    }

    private String readRequestBody(HttpServletRequest request) throws Exception {
        if (request instanceof McpRequestWrapper) {
            return ((McpRequestWrapper) request).getRequestBody();
        }
        return null;
    }
}
