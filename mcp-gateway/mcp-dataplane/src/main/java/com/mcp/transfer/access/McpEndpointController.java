package com.mcp.transfer.access;

import com.mcp.transfer.config.filter.UserTokenAuthenticator;
import com.mcp.transfer.config.session.McpInterceptorConstants;
import com.mcp.transfer.config.session.McpSessionCache;
import com.mcp.transfer.config.session.SseEmitterRegistry;
import com.mcp.transfer.shared.dto.mcp.req.McpRequest;
import com.mcp.transfer.shared.dto.mcp.res.McpResponse;
import com.mcp.transfer.gateway.McpUpstreamClient;
import com.mcp.transfer.gateway.aggregate.AggregateScope;
import com.mcp.transfer.gateway.aggregate.McpAggregateService;
import com.mcp.transfer.identity.McpIdentityContext;
import com.mcp.transfer.topology.EndpointPaths;
import com.mcp.transfer.topology.EndpointScope;
import com.mcp.transfer.topology.EndpointScopeReader;
import com.mcp.transfer.identity.ResolvedIdentity;
import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Endpoint 数据面：{@code /mcp/e/{endpointKey}}。
 *
 * <p>入口鉴权：x-dsh-auth-token → 用户身份；Endpoint 启用即可访问，
 * 按绑定的 Namespace 计算可见 Server 与工具前缀，再复用聚合服务。
 */
@Hidden
@RestController
public class McpEndpointController {

    private static final Logger logger = LoggerFactory.getLogger(McpEndpointController.class);
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;

    @Autowired
    private EndpointScopeReader topologyService;

    @Autowired
    private McpAggregateService aggregateService;

    @Autowired
    private UserTokenAuthenticator userTokenAuthenticator;

    @Autowired
    private McpSessionCache mcpSessionCache;

    @Autowired
    private SseEmitterRegistry sseEmitterRegistry;

    @Autowired
    private McpUpstreamClient mcpUpstreamClient;

    @PostMapping(path = EndpointPaths.PREFIX + "{endpointKey}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public McpResponse handle(@PathVariable String endpointKey,
                              @RequestBody McpRequest mcpRequest,
                              HttpServletRequest request,
                              HttpServletResponse response) throws Exception {
        ResolvedIdentity identity = userTokenAuthenticator.authenticate(request, response);
        if (identity == null) {
            return null;
        }
        EndpointScope endpointScope = topologyService.resolveScope(endpointKey);
        if (endpointScope == null) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return error(mcpRequest, -32001, "接入点不存在或已停用");
        }

        String method = mcpRequest == null ? null : mcpRequest.getMethod();
        logger.info("MCP Endpoint 请求 endpoint={} userId={} method={}", endpointKey, identity.getUserId(), method);

        if (method != null && McpInterceptorConstants.NO_CHECK_METHOD_LIST.contains(method)) {
            String sessionId = mcpSessionCache.generateSessionId();
            response.setHeader(McpInterceptorConstants.MCP_SESSION_ID_HEADER, sessionId);
            request.setAttribute(McpInterceptorConstants.MCP_SESSION_ID_HEADER, sessionId);
        }

        AggregateScope.set(new AggregateScope.Scope(
                endpointScope.getServerIds(), endpointScope.getPrefixByServer(),
                endpointScope.getPath(), endpointScope.getEndpoint().getName()));
        try {
            return aggregateService.handle(mcpRequest, request);
        } finally {
            AggregateScope.clear();
            McpIdentityContext.clear();
        }
    }

    @GetMapping(path = EndpointPaths.PREFIX + "{endpointKey}",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sse(@PathVariable String endpointKey,
                          HttpServletRequest request, HttpServletResponse response) {
        ResolvedIdentity identity = userTokenAuthenticator.authenticate(request, response);
        if (identity == null) {
            return null;
        }
        if (topologyService.resolveScope(endpointKey) == null) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return null;
        }
        String sessionId = request.getHeader(McpInterceptorConstants.MCP_SESSION_ID_HEADER);
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = mcpSessionCache.generateSessionId();
            response.setHeader(McpInterceptorConstants.MCP_SESSION_ID_HEADER, sessionId);
        } else if (!mcpSessionCache.validateSessionId(sessionId)) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return null;
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        // 带上 userId：全局配额之外再按用户限流，避免单客户端占满连接
        if (!sseEmitterRegistry.tryRegister(sessionId, null, emitter, identity.getUserId())) {
            response.setStatus(429);
            logger.warn("[SSE] Endpoint 连接数已达上限（全局或单用户），拒绝新连接 endpoint={} sessionId={} userId={}",
                    endpointKey, sessionId, identity.getUserId());
            return null;
        }
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (Exception ignore) {
            // 客户端可能已断开
        }
        return emitter;
    }

    @DeleteMapping(path = EndpointPaths.PREFIX + "{endpointKey}")
    public void delete(@PathVariable String endpointKey,
                       HttpServletRequest request, HttpServletResponse response) {
        if (userTokenAuthenticator.authenticate(request, response) == null) {
            return;
        }
        String sessionId = request.getHeader(McpInterceptorConstants.MCP_SESSION_ID_HEADER);
        if (sessionId != null && !sessionId.isEmpty()) {
            mcpSessionCache.removeSessionId(sessionId);
            sseEmitterRegistry.close(sessionId);
            mcpUpstreamClient.closeSession(sessionId);
        }
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    private McpResponse error(McpRequest request, int code, String message) {
        McpResponse response = new McpResponse();
        response.setId(request == null || request.getId() == null
                ? java.util.UUID.randomUUID().toString() : request.getId());
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        response.setError(err);
        return response;
    }
}
