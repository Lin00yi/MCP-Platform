package com.mcp.transfer.config.filter;

import com.alibaba.fastjson.JSON;
import com.mcp.transfer.shared.utils.EnvUtils;
import com.mcp.transfer.shared.utils.TraceIdUtils;
import com.mcp.transfer.config.session.McpInterceptorConstants;
import com.mcp.transfer.config.session.McpSessionCache;
import com.mcp.transfer.shared.dto.mcp.req.McpRequest;
import com.mcp.transfer.shared.enums.McpErrorCode;
import com.mcp.transfer.shared.enums.McpMethodEnum;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;

/**
 * MCP Session-ID拦截器：校验请求头中的mcp-session-id
 */
@Slf4j
@Component
public class McpSessionInterceptor implements HandlerInterceptor {


    private static final Logger logger = LoggerFactory.getLogger(McpSessionInterceptor.class);

    @Autowired
    private McpSessionCache mcpSessionCache;

    @Autowired
    private EnvUtils envUtils;

    /** 是否校验 Mcp-Session-Id（MCP Streamable HTTP 要求，默认开启；兼容旧客户端可设为 false） */
    @Value("${mcp.session.enabled:true}")
    private boolean sessionEnabled;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        // 1. 获取请求头中的 traceId（上游服务传递的，没有则生成新的）
        String traceId = request.getHeader(TraceIdUtils.TRACE_ID_HEADER);
        if (traceId == null || traceId.isEmpty()) {
            traceId = TraceIdUtils.generateTraceId();
        }

        // 2. 设置 traceId 到 ThreadLocal 和 MDC
        TraceIdUtils.setTraceId(traceId);

        // 3. 将 traceId 放入响应头，便于前端/下游服务获取
        response.setHeader(TraceIdUtils.TRACE_ID_HEADER, traceId);

        // 4. 读取请求体中的MCP方法名（判断是否在过滤列表）
        String requestBody = readRequestBody(request);
        McpRequest mcpRequest;
        try {
            mcpRequest = JSON.parseObject(requestBody, McpRequest.class);
        } catch (Exception e) {
            // 非 JSON 请求（表单、纯文本等）交给 MVC 处理，不能在这里抛 500
            return true;
        }
        if (null == mcpRequest) {
            return true;
        }
        String method = mcpRequest.getMethod();

        // 仅对 MCP 协议方法校验 session；非 MCP 请求（如 /mcp/admin/**、/mcp/log/**）直接放行
        if (method == null || McpMethodEnum.getByMethod(method) == null) {
            return true;
        }

        // 3. 如果是过滤列表中的方法（初始化），直接放行
        if (McpInterceptorConstants.NO_CHECK_METHOD_LIST.contains(method)) {
            logger.info("MCP请求方法{}在过滤列表中，跳过Session-ID校验", method);
            return true;
        }

        // 兼容开关：mcp.session.enabled=false 时跳过 Session-ID 校验（旧客户端）
        if (!sessionEnabled) {
            logger.info("MCP Session 校验已关闭（mcp.session.enabled=false），跳过");
            return true;
        }

        // 4. 非过滤列表方法，校验Session-ID
        String sessionId = request.getHeader(McpInterceptorConstants.MCP_SESSION_ID_HEADER);
        boolean isValid = mcpSessionCache.validateSessionId(sessionId);
        if (!isValid) {
            // 校验失败，返回400错误（MCP标准错误码-32600）
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            PrintWriter writer = response.getWriter();
            writer.write(JSON.toJSONString(McpErrorCode.INVALID_REQUEST));
            writer.flush();
            writer.close();
            // preHandle 返回 false 时 Spring 不会回调 afterCompletion，需手动清理 ThreadLocal
            TraceIdUtils.clear();
            return false; // 拦截请求，不放行
        }


        // 5. 校验通过，放行
        return true;
    }

    /**
     * 读取HTTP请求体（重复读取，避免流关闭）
     */
    private String readRequestBody(HttpServletRequest request) throws Exception {
        // 需包装request，支持重复读取流（否则Controller中无法再次读取）
        if (request instanceof McpRequestWrapper) {
            return ((McpRequestWrapper) request).getRequestBody();
        }
        // 首次读取，包装request
        McpRequestWrapper requestWrapper = new McpRequestWrapper(request);
        request.setAttribute("requestWrapper", requestWrapper);
        return requestWrapper.getRequestBody();
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 请求结束后清除 traceId，防止 ThreadLocal 内存泄漏
        TraceIdUtils.clear();
    }
}