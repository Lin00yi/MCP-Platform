package com.mcp.transfer.admin.controller;

import com.mcp.transfer.admin.topology.AccessTopologyAdminService;
import com.mcp.transfer.shared.dto.Result;
import com.mcp.transfer.shared.entity.McpEndpoint;
import com.mcp.transfer.shared.entity.McpNamespace;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Namespace / Endpoint 管理接口（控制面）。 */
@RestController
@RequestMapping("/mcp/admin")
public class McpAdminAccessController {

    @Autowired
    private AccessTopologyAdminService topologyService;

    // ==================== Namespace ====================

    @Operation(summary = "Namespace 列表")
    @GetMapping("/namespaces")
    public Result<List<Map<String, Object>>> listNamespaces() {
        return Result.success(topologyService.listNamespaces());
    }

    @Operation(summary = "创建 Namespace")
    @PostMapping("/namespaces")
    public Result<McpNamespace> createNamespace(@RequestBody Map<String, Object> body) {
        try {
            McpNamespace ns = topologyService.saveNamespace(null,
                    str(body, "name"), str(body, "prefix"), str(body, "description"), integer(body, "status"),
                    integer(body, "tenantId"));
            return Result.success(ns);
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    @Operation(summary = "更新 Namespace")
    @PutMapping("/namespaces/{id}")
    public Result<McpNamespace> updateNamespace(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        try {
            McpNamespace ns = topologyService.saveNamespace(id,
                    str(body, "name"), str(body, "prefix"), str(body, "description"), integer(body, "status"),
                    null);
            return Result.success(ns);
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    @Operation(summary = "删除 Namespace")
    @DeleteMapping("/namespaces/{id}")
    public Result<Void> deleteNamespace(@PathVariable Integer id) {
        try {
            topologyService.deleteNamespace(id);
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    @Operation(summary = "设置 Namespace 成员 Server")
    @PutMapping("/namespaces/{id}/servers")
    public Result<Void> setNamespaceServers(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        try {
            topologyService.setNamespaceServers(id, integerList(body.get("serverIds")));
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    // ==================== Endpoint ====================

    @Operation(summary = "Endpoint 列表")
    @GetMapping("/endpoints")
    public Result<List<Map<String, Object>>> listEndpoints() {
        return Result.success(topologyService.listEndpoints());
    }

    @Operation(summary = "创建 Endpoint")
    @PostMapping("/endpoints")
    public Result<McpEndpoint> createEndpoint(@RequestBody Map<String, Object> body) {
        try {
            McpEndpoint ep = topologyService.saveEndpoint(null,
                    str(body, "endpointKey"), str(body, "name"), str(body, "description"), integer(body, "status"),
                    integer(body, "tenantId"));
            return Result.success(ep);
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    @Operation(summary = "更新 Endpoint")
    @PutMapping("/endpoints/{id}")
    public Result<McpEndpoint> updateEndpoint(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        try {
            McpEndpoint ep = topologyService.saveEndpoint(id,
                    str(body, "endpointKey"), str(body, "name"), str(body, "description"), integer(body, "status"),
                    null);
            return Result.success(ep);
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    @Operation(summary = "删除 Endpoint")
    @DeleteMapping("/endpoints/{id}")
    public Result<Void> deleteEndpoint(@PathVariable Integer id) {
        try {
            topologyService.deleteEndpoint(id);
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    @Operation(summary = "设置 Endpoint 绑定的 Namespace")
    @PutMapping("/endpoints/{id}/namespaces")
    public Result<Void> setEndpointNamespaces(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        try {
            topologyService.setEndpointNamespaces(id, integerList(body.get("namespaceIds")));
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    // ==================== helpers ====================

    private String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private Integer integer(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null || "".equals(v)) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<Integer> integerList(Object value) {
        List<Integer> result = new java.util.ArrayList<>();
        if (value instanceof List) {
            for (Object o : (List<?>) value) {
                if (o == null) {
                    continue;
                }
                try {
                    result.add(Integer.valueOf(String.valueOf(o)));
                } catch (NumberFormatException ignore) {
                    // skip invalid
                }
            }
        }
        return result;
    }

    private List<String> stringList(Object value) {
        List<String> result = new java.util.ArrayList<>();
        if (value instanceof List) {
            for (Object o : (List<?>) value) {
                if (o != null && org.springframework.util.StringUtils.hasText(String.valueOf(o))) {
                    result.add(String.valueOf(o).trim());
                }
            }
        }
        return result;
    }
}
