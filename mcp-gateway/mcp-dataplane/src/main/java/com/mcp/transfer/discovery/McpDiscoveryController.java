package com.mcp.transfer.discovery;

import com.mcp.transfer.catalog.McpDiscoveryService;

import com.mcp.transfer.config.filter.McpDiscoveryAuthInterceptor;
import com.mcp.transfer.config.session.McpSessionCache;
import com.mcp.transfer.config.session.SseEmitterRegistry;
import com.mcp.transfer.shared.dto.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;

/**
 * 客户端 MCP 发现接口。
 * 统一前缀 /mcp/discovery，<b>必须携带有效 x-dsh-auth-token</b>（由 McpDiscoveryAuthInterceptor 校验）。
 */
@Tag(name = "MCP 发现")
@RestController
@RequestMapping("/mcp/discovery")
public class McpDiscoveryController {

    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;

    @Autowired
    private McpDiscoveryService discoveryService;

    @Autowired
    private McpSessionCache mcpSessionCache;

    @Autowired
    private SseEmitterRegistry sseEmitterRegistry;

    private String appId(HttpServletRequest request) {
        Object attr = request.getAttribute(McpDiscoveryAuthInterceptor.DISCOVERY_APP_ID_ATTR);
        return attr == null ? null : attr.toString();
    }

    /** 当前登录用户 id（由 McpDiscoveryAuthInterceptor 写入），用于 SSE 单用户配额 */
    private String userId(HttpServletRequest request) {
        Object attr = request.getAttribute(com.mcp.transfer.config.filter.UserTokenAuthenticator.USER_ID_ATTR);
        return attr == null ? null : attr.toString();
    }

    @Operation(summary = "Server 目录（含 DSH 同步元数据）")
    @GetMapping("/servers")
    public Result<List<Map<String, Object>>> listServers(HttpServletRequest request,
                                                         HttpServletResponse response) {
        List<Map<String, Object>> servers = discoveryService.listServers(appId(request), null, false);
        String revision = discoveryService.revisionOf(servers);
        String etag = "\"" + revision + "\"";
        response.setHeader("ETag", etag);
        response.setHeader("X-MCP-Revision", revision);
        response.setHeader("Cache-Control", "no-cache");
        String ifNoneMatch = request.getHeader("If-None-Match");
        if (etag.equals(ifNoneMatch) || revision.equals(ifNoneMatch)) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return null;
        }
        return Result.success(servers);
    }

    @Operation(summary = "工具目录（跨 server 检索）")
    @GetMapping("/tools")
    public Result<List<Map<String, Object>>> listTools(
            HttpServletRequest request,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) Integer serverId) {
        return Result.success(discoveryService.listTools(appId(request), q, tag, serverId, null));
    }

    @Operation(summary = "Prompt 目录")
    @GetMapping("/prompts")
    public Result<List<Map<String, Object>>> listPrompts(
            HttpServletRequest request,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) Integer serverId) {
        return Result.success(discoveryService.listPrompts(appId(request), serverId, q, tag, null));
    }

    @Operation(summary = "Resource 目录")
    @GetMapping("/resources")
    public Result<List<Map<String, Object>>> listResources(
            HttpServletRequest request,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) Integer serverId) {
        return Result.success(discoveryService.listResources(appId(request), serverId, q, tag, null));
    }

    @Operation(summary = "Server 健康探测")
    @GetMapping("/servers/{id}/health")
    public Result<Map<String, Object>> health(@PathVariable Integer id, HttpServletRequest request) {
        if (!discoveryService.canAccessServer(appId(request), id)) {
            return Result.fail(403, "无权限访问该 Server");
        }
        return Result.success(discoveryService.health(id, null));
    }

    @Operation(summary = "Server 变更订阅（SSE）")
    @GetMapping("/servers/{id}/changes")
    public SseEmitter changes(@PathVariable Integer id, HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (!discoveryService.canAccessServer(appId(request), id)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return null;
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        String sessionId = mcpSessionCache.generateSessionId();
        // 带上 userId：全局配额之外再按用户限流
        String userId = userId(request);
        if (!sseEmitterRegistry.tryRegister(sessionId, id, emitter, userId)) {
            response.setStatus(429);
            return null;
        }
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (Exception ignore) {
            // 客户端可能已断开
        }
        return emitter;
    }
}
