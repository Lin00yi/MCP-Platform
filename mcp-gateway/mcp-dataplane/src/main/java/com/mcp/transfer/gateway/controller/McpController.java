package com.mcp.transfer.gateway.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.mcp.transfer.config.session.McpInterceptorConstants;
import com.mcp.transfer.config.session.McpSessionCache;
import com.mcp.transfer.config.session.SseEmitterRegistry;
import com.mcp.transfer.dto.mcp.req.McpRequest;
import com.mcp.transfer.dto.mcp.res.McpResponse;
import com.mcp.transfer.gateway.service.McpService;
import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * MCP 公共处理方法（支持 MCP Streamable HTTP 传输）。
 *
 * <p>本类不再由组件扫描注册 RequestMapping：所有对外路径由
 * {@code McpServerDynamicControllerManager} 动态绑定到同一个单例 Bean，
 * 避免“先删后建”导致的路由真空期。
 */
@Component
@ResponseBody
@Hidden
public class McpController {

    private static final Logger logger = LoggerFactory.getLogger(McpController.class);

    @Autowired
    private McpService mcpService;

    @Autowired
    private McpSessionCache mcpSessionCache;

    @Autowired
    private SseEmitterRegistry sseEmitterRegistry;

    @Autowired
    private com.mcp.transfer.gateway.McpUpstreamClient mcpUpstreamClient;

    /** SSE 连接超时：30 分钟（与 session 过期一致） */
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;

    /**
     * 核心处理方法（POST，JSON-RPC），由动态注册器绑定到各 server path。
     */
    public McpResponse handleMcpRequest(@RequestBody McpRequest mcpRequest,
                                        HttpServletRequest request,
                                        HttpServletResponse servletResponse) {
        String method = mcpRequest == null ? null : mcpRequest.getMethod();
        // 仅记录方法/路径，避免把工具入参（可能含敏感信息）整包写入日志
        logger.info("MCP 请求 method={}, path={}", method, request.getRequestURI());

        // Streamable HTTP：initialize 生成并回写 Mcp-Session-Id
        if (method != null && McpInterceptorConstants.NO_CHECK_METHOD_LIST.contains(method)) {
            String sessionId = mcpSessionCache.generateSessionId();
            servletResponse.setHeader(McpInterceptorConstants.MCP_SESSION_ID_HEADER, sessionId);
            request.setAttribute(McpInterceptorConstants.MCP_SESSION_ID_HEADER, sessionId);
        }

        // 通知（JSON-RPC 无 id）→ 202 Accepted，无响应体
        boolean isNotification = mcpRequest == null || mcpRequest.getId() == null;

        McpResponse response;
        try {
            response = mcpService.handleMcpRequest(mcpRequest, request);
        } catch (Exception e) {
            logger.error("MCP 接口处理异常, method={}, path={}", method, request.getRequestURI(), e);
            response = errorResponse(mcpRequest, -32603, "服务器内部错误");
        } catch (Throwable t) {
            logger.error("MCP 接口处理发生 Error 级别异常, method={}, path={}", method, request.getRequestURI(), t);
            response = errorResponse(mcpRequest, -32603, "服务器内部错误: " + t.getClass().getSimpleName());
        }

        if (isNotification) {
            servletResponse.setStatus(HttpServletResponse.SC_ACCEPTED);
            return null;
        }
        return response;
    }

    private McpResponse errorResponse(McpRequest request, int code, String message) {
        McpResponse response = new McpResponse();
        response.setId(request == null ? null : request.getId());
        JSONObject error = new JSONObject();
        error.put("code", code);
        error.put("message", message);
        response.setError(error);
        return response;
    }

    /**
     * GET（SSE）：打开 Streamable HTTP 的 server→client 流。
     */
    public SseEmitter handleSseConnection(HttpServletRequest request, HttpServletResponse servletResponse) {
        String sessionId = request.getHeader(McpInterceptorConstants.MCP_SESSION_ID_HEADER);
        boolean isNew = (sessionId == null || sessionId.isEmpty());
        if (isNew) {
            sessionId = mcpSessionCache.generateSessionId();
            servletResponse.setHeader(McpInterceptorConstants.MCP_SESSION_ID_HEADER, sessionId);
            request.setAttribute(McpInterceptorConstants.MCP_SESSION_ID_HEADER, sessionId);
        } else if (!mcpSessionCache.validateSessionId(sessionId)) {
            servletResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return null;
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Integer serverId = mcpService.resolveServerIdByPath(request.getRequestURI());
        if (!sseEmitterRegistry.tryRegister(sessionId, serverId, emitter)) {
            servletResponse.setStatus(429);
            logger.warn("[SSE] 连接数已达上限，拒绝新连接 sessionId={}", sessionId);
            return null;
        }
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (Exception ignore) {
            // 客户端可能已断开
        }
        logger.info("打开 MCP SSE 连接，sessionId={}", sessionId);
        return emitter;
    }

    /**
     * DELETE：终止会话并关闭对应 SSE 连接。
     */
    public void handleSessionTermination(HttpServletRequest request, HttpServletResponse servletResponse) {
        String sessionId = request.getHeader(McpInterceptorConstants.MCP_SESSION_ID_HEADER);
        if (sessionId != null && !sessionId.isEmpty()) {
            mcpSessionCache.removeSessionId(sessionId);
            sseEmitterRegistry.close(sessionId);
            mcpUpstreamClient.closeSession(sessionId);
            logger.info("终止 MCP 会话，sessionId={}", sessionId);
        }
        servletResponse.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }
}
