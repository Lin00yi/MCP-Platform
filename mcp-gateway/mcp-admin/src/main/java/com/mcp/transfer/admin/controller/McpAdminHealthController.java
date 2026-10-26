package com.mcp.transfer.admin.controller;

import com.mcp.transfer.admin.security.AdminAuthContext;
import com.mcp.transfer.shared.dto.Result;
import com.mcp.transfer.shared.entity.McpServer;
import com.mcp.transfer.shared.mapper.McpServerMapper;
import com.mcp.transfer.port.HealthQueryPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP Server 健康状态（管理台）。
 *
 * <p>探测与结果缓存都在数据面（它才有上游出网权限与 SSRF 校验）；
 * 控制面只做「租户过滤 + 展示」，通过 {@link HealthQueryPort} 读取。
 * 这样管理台刷新健康页不会再额外打一遍上游。
 */
@Tag(name = "健康检测")
@RestController
@RequestMapping("/mcp/admin/health")
public class McpAdminHealthController {

    @Autowired
    private McpServerMapper mcpServerMapper;

    @Autowired
    private HealthQueryPort healthQueryPort;

    private List<Integer> visibleTenantIds() {
        AdminAuthContext ctx = AdminAuthContext.get();
        if (ctx == null || ctx.isSuperAdmin()) {
            return null;
        }
        return ctx.getTenantIds() == null ? Collections.emptyList() : ctx.getTenantIds();
    }

    private boolean canAccess(McpServer server) {
        if (server == null) {
            return false;
        }
        List<Integer> tenantIds = visibleTenantIds();
        return tenantIds == null || tenantIds.contains(server.getTenantId());
    }

    @Operation(summary = "全部 Server 健康状态（列表页批量）")
    @GetMapping("/servers")
    public Result<List<Map<String, Object>>> listAll() {
        List<Integer> tenantIds = visibleTenantIds();
        if (tenantIds != null && tenantIds.isEmpty()) {
            return Result.success(new ArrayList<>());
        }
        try {
            List<Map<String, Object>> all = new ArrayList<>(healthQueryPort.listAll());
            if (tenantIds != null) {
                Map<Integer, Integer> tenantByServer = tenantIndex();
                all = all.stream()
                        .filter(m -> {
                            Integer tenantId = tenantByServer.get(toInt(m.get("serverId")));
                            return tenantId != null && tenantIds.contains(tenantId);
                        })
                        .collect(Collectors.toList());
            }
            return Result.success(all);
        } catch (HealthQueryPort.UnavailableException e) {
            return Result.fail(503, e.getMessage());
        }
    }

    @Operation(summary = "单个 Server 健康详情")
    @GetMapping("/servers/{id}")
    public Result<Map<String, Object>> one(@PathVariable Integer id) {
        McpServer server = mcpServerMapper.selectByPrimaryKey(id);
        if (!canAccess(server)) {
            return Result.fail(404, "Server 不存在或无权限");
        }
        try {
            Map<String, Object> health = healthQueryPort.one(id);
            if (health == null) {
                return Result.fail(404, "Server 不存在或无权限");
            }
            return Result.success(health);
        } catch (HealthQueryPort.UnavailableException e) {
            return Result.fail(503, e.getMessage());
        }
    }

    @Operation(summary = "立即探测（跳过缓存）")
    @PostMapping("/servers/{id}/probe")
    public Result<Map<String, Object>> probe(@PathVariable Integer id) {
        McpServer server = mcpServerMapper.selectByPrimaryKey(id);
        if (!canAccess(server)) {
            return Result.fail(404, "Server 不存在或无权限");
        }
        try {
            Map<String, Object> health = healthQueryPort.probe(id);
            if (health == null) {
                return Result.fail(404, "Server 不存在或无权限");
            }
            return Result.success(health);
        } catch (HealthQueryPort.UnavailableException e) {
            return Result.fail(503, e.getMessage());
        }
    }

    private Map<Integer, Integer> tenantIndex() {
        Map<Integer, Integer> map = new HashMap<>();
        List<McpServer> servers = mcpServerMapper.selectAll();
        if (servers != null) {
            for (McpServer server : servers) {
                if (server.getId() != null) {
                    map.put(server.getId(), server.getTenantId());
                }
            }
        }
        return map;
    }

    private Integer toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
