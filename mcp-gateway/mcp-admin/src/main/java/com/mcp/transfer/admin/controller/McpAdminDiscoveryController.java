package com.mcp.transfer.admin.controller;

import com.mcp.transfer.admin.security.AdminAuthContext;
import com.mcp.transfer.catalog.McpDiscoveryService;
import com.mcp.transfer.dto.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 管理台「发现预览」：以指定应用的视角预览 MCP 客户端会发现的 server/工具。
 *
 * <p>与对外 {@code /mcp/discovery/**} 复用同一个 {@link McpDiscoveryService}，
 * 入口鉴权换成 admin JWT，并按管理员可见租户过滤，避免跨租户预览。
 */
@Tag(name = "发现预览")
@RestController
@RequestMapping("/mcp/admin/discovery")
public class McpAdminDiscoveryController {

    @Autowired
    private McpDiscoveryService discoveryService;

    /** 当前管理员可见租户：超管 null（不过滤），租户用户返回自身租户列表（可能为空） */
    private List<Integer> visibleTenantIds() {
        AdminAuthContext ctx = AdminAuthContext.get();
        if (ctx == null || ctx.isSuperAdmin()) {
            return null;
        }
        return ctx.getTenantIds() == null ? Collections.emptyList() : ctx.getTenantIds();
    }

    @Operation(summary = "以指定应用视角预览 Server 目录")
    @GetMapping("/servers")
    public Result<List<Map<String, Object>>> servers(@RequestParam(required = false) String appId) {
        return Result.success(discoveryService.listServers(appId, visibleTenantIds(), true));
    }

    @Operation(summary = "以指定应用视角预览工具目录")
    @GetMapping("/tools")
    public Result<List<Map<String, Object>>> tools(
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) Integer serverId) {
        return Result.success(discoveryService.listTools(appId, q, tag, serverId, visibleTenantIds(), true));
    }

    @Operation(summary = "以指定应用视角预览 Prompt 目录")
    @GetMapping("/prompts")
    public Result<List<Map<String, Object>>> prompts(
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) Integer serverId) {
        return Result.success(discoveryService.listPrompts(appId, serverId, q, tag, visibleTenantIds()));
    }

    @Operation(summary = "以指定应用视角预览 Resource 目录")
    @GetMapping("/resources")
    public Result<List<Map<String, Object>>> resources(
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) Integer serverId) {
        return Result.success(discoveryService.listResources(appId, serverId, q, tag, visibleTenantIds()));
    }

    @Operation(summary = "Server 健康探测")
    @GetMapping("/servers/{id}/health")
    public Result<Map<String, Object>> health(@PathVariable Integer id) {
        Map<String, Object> health = discoveryService.health(id, visibleTenantIds());
        if (Boolean.FALSE.equals(health.get("ok")) && "server 不存在".equals(health.get("message"))) {
            return Result.fail(404, "Server 不存在或无权限");
        }
        return Result.success(health);
    }
}
