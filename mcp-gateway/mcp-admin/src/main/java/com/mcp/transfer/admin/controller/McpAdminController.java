package com.mcp.transfer.admin.controller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.mcp.transfer.admin.approval.ApprovalPayloads;
import com.mcp.transfer.admin.approval.ApprovalPolicy;
import com.mcp.transfer.admin.service.AdminAuthService;
import com.mcp.transfer.admin.service.openapi.OpenApiImportPlan;
import com.mcp.transfer.admin.service.openapi.OpenApiIssue;
import com.mcp.transfer.admin.service.openapi.OpenApiToolDraft;
import com.mcp.transfer.admin.service.openapi.OpenApiToolParser;
import com.mcp.transfer.admin.security.AdminAuthContext;
import com.mcp.transfer.config.security.McpPathValidator;
import com.mcp.transfer.config.security.UrlSecurityValidator;
import org.springframework.transaction.annotation.Transactional;
import com.mcp.transfer.dto.AdminUserRequest;
import com.mcp.transfer.dto.ApprovalCreateRequest;
import com.mcp.transfer.dto.ApprovalDecisionRequest;
import com.mcp.transfer.dto.BulkMoveToolsRequest;
import com.mcp.transfer.dto.PageResult;
import com.mcp.transfer.dto.Result;
import com.mcp.transfer.dto.ToolExposureRequest;
import com.mcp.transfer.entity.AdminUser;
import com.mcp.transfer.entity.ApprovalRequest;
import com.mcp.transfer.entity.McpPrompt;
import com.mcp.transfer.entity.McpResource;
import com.mcp.transfer.entity.McpServer;
import com.mcp.transfer.entity.McpTool;
import com.mcp.transfer.entity.OperationLog;
import com.mcp.transfer.entity.ServiceSystem;
import com.mcp.transfer.entity.Tenant;
import com.mcp.transfer.entity.ToolCallRecord;
import com.mcp.transfer.enums.ApprovalAction;
import com.mcp.transfer.enums.ApprovalTier;
import com.mcp.transfer.enums.RoleEnum;
import com.mcp.transfer.mapper.AdminUserMapper;
import com.mcp.transfer.mapper.ApprovalRequestMapper;
import com.mcp.transfer.mapper.McpPromptMapper;
import com.mcp.transfer.mapper.McpRequestLogMapper;
import com.mcp.transfer.mapper.McpResourceMapper;
import com.mcp.transfer.mapper.McpServerMapper;
import com.mcp.transfer.mapper.McpToolMapper;
import com.mcp.transfer.mapper.OperationLogMapper;
import com.mcp.transfer.mapper.ServiceSystemMapper;
import com.mcp.transfer.mapper.TenantMapper;
import com.mcp.transfer.mapper.ToolCallRecordMapper;
import com.mcp.transfer.port.ConfigChangePublisher;
import com.mcp.transfer.port.EmbeddingAdminPort;
import com.mcp.transfer.port.ToolConnectivityProbe;
import com.mcp.transfer.utils.RestToolDraftBuilder;
import com.mcp.transfer.utils.SecretCrypto;
import com.mcp.transfer.utils.ServerKeyUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MCP 网关管理后台 REST 接口（多租户：业务方 → MCP Server → tool/prompt/resource；应用 × server 授权）。
 * 为控制面板（mcp-console）提供统一 CRUD。
 *
 * 注意：生产环境需在网关层或拦截器层增加管理员鉴权，本控制器默认不校验登录态。
 */
@Slf4j
@Tag(name = "MCP 管理接口")
@RestController
@RequestMapping("/mcp/admin")
public class McpAdminController {

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private McpServerMapper mcpServerMapper;

    @Autowired
    private McpToolMapper mcpToolMapper;

    @Autowired
    private McpPromptMapper mcpPromptMapper;

    @Autowired
    private McpResourceMapper mcpResourceMapper;

    @Autowired
    private ServiceSystemMapper serviceSystemMapper;

    @Autowired
    private ToolCallRecordMapper toolCallRecordMapper;

    @Autowired
    private McpRequestLogMapper mcpRequestLogMapper;

    @Autowired
    private com.mcp.transfer.exposure.ToolAccessPolicy toolAccessPolicy;

    @Autowired
    private com.mcp.transfer.exposure.ToolExposureProperties toolExposureProperties;

    @Autowired
    private com.mcp.transfer.exposure.ToolPaginationTelemetry toolPaginationTelemetry;

    /** 向量索引由数据面维护（embedding 凭证只在数据面），控制面只发指令 */
    @Autowired
    private EmbeddingAdminPort embeddingAdminPort;

    @Autowired
    private com.mcp.transfer.exposure.TokenEstimator tokenEstimator;

    @Autowired
    private SecretCrypto secretCrypto;

    @Autowired
    private OperationLogMapper operationLogMapper;

    @Autowired
    private ApprovalRequestMapper approvalRequestMapper;

    @Autowired
    private AdminUserMapper adminUserMapper;

    @Autowired
    private AdminAuthService adminAuthService;

    @Autowired
    private com.mcp.transfer.mapper.AdminUserTenantMapper adminUserTenantMapper;

    /** 数据面能力端口：连通性探测（单进程为本地实现，物理拆分后为远程实现） */
    @Autowired
    private ToolConnectivityProbe toolConnectivityProbe;

    /** 配置变更端口：失效数据面缓存 / 刷新动态路由 / 广播 list_changed */
    @Autowired
    private ConfigChangePublisher configChangePublisher;

    @Autowired
    private UrlSecurityValidator urlSecurityValidator;

    // ============ 业务方 tenant ============

    @Operation(summary = "业务方列表")
    @GetMapping("/tenants")
    public Result<List<Tenant>> listTenants() {
        List<Tenant> list = tenantMapper.selectAll();
        List<Integer> tenantIds = visibleTenantIds();
        if (tenantIds != null) {
            list = list.stream().filter(t -> tenantIds.contains(t.getId())).collect(Collectors.toList());
        }
        return Result.success(list);
    }
    @Operation(summary = "创建业务方")
    @PostMapping("/tenants")
    public Result<Tenant> createTenant(@RequestBody Tenant tenant) {
        String approvalErr = denyDirect(ApprovalAction.TENANT_CREATE);
        if (approvalErr != null) {
            return Result.fail(428, approvalErr);
        }
        Date now = new Date();
        tenant.setCreateTime(now);
        tenant.setUpdateTime(now);
        if (tenant.getStatus() == null) {
            tenant.setStatus(1);
        }
        tenantMapper.insertSelective(tenant);
        recordOperation("CREATE", "TENANT", tenant.getId(), tenant.getName());
        return Result.success(tenant);
    }

    @Operation(summary = "更新业务方")
    @PutMapping("/tenants/{id}")
    public Result<Tenant> updateTenant(@PathVariable Integer id, @RequestBody Tenant tenant) {
        String approvalErr = denyDirect(ApprovalAction.TENANT_UPDATE);
        if (approvalErr != null) {
            return Result.fail(428, approvalErr);
        }
        tenant.setId(id);
        tenant.setUpdateTime(new Date());
        tenantMapper.updateByPrimaryKeySelective(tenant);
        recordOperation("UPDATE", "TENANT", id, tenant.getName());
        return Result.success(tenantMapper.selectByPrimaryKey(id));
    }

    @Operation(summary = "删除业务方")
    @DeleteMapping("/tenants/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> deleteTenant(@PathVariable Integer id) {
        String approvalErr = denyDirect(ApprovalAction.TENANT_DELETE);
        if (approvalErr != null) {
            return Result.fail(428, approvalErr);
        }
        List<McpServer> servers = mcpServerMapper.selectByTenantId(id);
        if (!CollectionUtils.isEmpty(servers)) {
            return Result.fail("该业务方下仍有 " + servers.size() + " 个 MCP Server，请先删除后再删除业务方");
        }
        adminUserTenantMapper.deleteByTenantId(id);
        tenantMapper.deleteByPrimaryKey(id);
        recordOperation("DELETE", "TENANT", id, null, id);
        return Result.success();
    }

    // ============ MCP Server ============

    @Operation(summary = "MCP Server 列表")
    @GetMapping("/servers")
    public Result<List<McpServer>> listServers() {
        List<Integer> tenantIds = visibleTenantIds();
        if (tenantIds == null) {
            return Result.success(mcpServerMapper.selectAll());
        }
        if (tenantIds.isEmpty()) {
            return Result.success(new ArrayList<>());
        }
        return Result.success(mcpServerMapper.selectByTenantIds(tenantIds));
    }

    @Operation(summary = "创建 MCP Server")
    @PostMapping("/servers")
    @Transactional(rollbackFor = Exception.class)
    public Result<McpServer> createServer(@RequestBody McpServer server) {
        String approvalErr = denyDirect(ApprovalAction.SERVER_CREATE);
        if (approvalErr != null) {
            return Result.fail(428, approvalErr);
        }
        return doCreateServer(server);
    }

    /** 创建 Server 的实际逻辑（审批通过后在同一事务内复用） */
    public Result<McpServer> doCreateServer(McpServer server) {
        if (!canManageTenantId(server.getTenantId())) {
            return Result.fail("无权限在该业务方下创建 Server");
        }
        if (!StringUtils.hasText(server.getPath())) {
            return Result.fail("路径 path 不能为空");
        }
        try {
            server.setPath(McpPathValidator.validateAndNormalize(server.getPath()));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
        if (mcpServerMapper.selectOneByPath(server.getPath()) != null) {
            return Result.fail("路径已被占用：" + server.getPath());
        }
        // capabilities_json：空则给默认（protocolVersion + serverInfo），非空校验合法 JSON
        if (!StringUtils.hasText(server.getCapabilitiesJson())) {
            com.alibaba.fastjson.JSONObject def = new com.alibaba.fastjson.JSONObject();
            def.put("protocolVersion", "2024-11-05");
            com.alibaba.fastjson.JSONObject info = new com.alibaba.fastjson.JSONObject();
            info.put("name", server.getName());
            info.put("version", "1.0.0");
            def.put("serverInfo", info);
            server.setCapabilitiesJson(def.toJSONString());
        } else {
            try {
                if (com.alibaba.fastjson.JSON.parseObject(server.getCapabilitiesJson()) == null) {
                    return Result.fail("capabilities_json 不是合法 JSON");
                }
            } catch (Exception e) {
                return Result.fail("capabilities_json 解析失败：" + e.getMessage());
            }
        }
        normalizeUpstream(server);
        if (("MCP".equals(server.getUpstreamType()) || "AGGREGATE".equals(server.getUpstreamType()))
                && !StringUtils.hasText(server.getUpstreamUrl())) {
            return Result.fail("MCP/聚合代理型 server 必须填写 upstream_url（下游 MCP server 地址，聚合为 JSON 数组）");
        }
        Date now = new Date();
        server.setServerKey(null); // 先插入，拿到 id 后再生成稳定 key
        server.setCreateTime(now);
        server.setUpdateTime(now);
        if (server.getStatus() == null) {
            server.setStatus(1);
        }
        // 应用授权已移除：新建 server 默认 ALL（全部启用资产对登录用户可见）
        if (!StringUtils.hasText(server.getToolAccessMode())) {
            server.setToolAccessMode(com.mcp.transfer.exposure.ToolExposureModes.ACCESS_ALL);
        } else if (!com.mcp.transfer.exposure.ToolExposureModes.isValidAccessMode(server.getToolAccessMode())) {
            return Result.fail("toolAccessMode 仅支持 WHITELIST / ALL");
        } else {
            server.setToolAccessMode(com.mcp.transfer.exposure.ToolExposureModes
                    .normalizeAccessMode(server.getToolAccessMode()));
        }
        if (!StringUtils.hasText(server.getToolExposureMode())) {
            server.setToolExposureMode(com.mcp.transfer.exposure.ToolExposureModes.EXPOSURE_DIRECT);
        } else if (!com.mcp.transfer.exposure.ToolExposureModes.isValidExposureMode(server.getToolExposureMode())) {
            return Result.fail("toolExposureMode 仅支持 DIRECT / META");
        } else {
            server.setToolExposureMode(com.mcp.transfer.exposure.ToolExposureModes
                    .normalizeExposureMode(server.getToolExposureMode()));
        }
        if (!StringUtils.hasText(server.getPromptAccessMode())) {
            server.setPromptAccessMode(com.mcp.transfer.exposure.ToolExposureModes.ACCESS_ALL);
        } else if (!com.mcp.transfer.exposure.ToolExposureModes.isValidAssetAccessMode(server.getPromptAccessMode())) {
            return Result.fail("promptAccessMode 仅支持 WHITELIST / ALL");
        } else {
            server.setPromptAccessMode(com.mcp.transfer.exposure.ToolExposureModes
                    .normalizeAssetAccessMode(server.getPromptAccessMode()));
        }
        if (!StringUtils.hasText(server.getResourceAccessMode())) {
            server.setResourceAccessMode(com.mcp.transfer.exposure.ToolExposureModes.ACCESS_ALL);
        } else if (!com.mcp.transfer.exposure.ToolExposureModes.isValidAssetAccessMode(server.getResourceAccessMode())) {
            return Result.fail("resourceAccessMode 仅支持 WHITELIST / ALL");
        } else {
            server.setResourceAccessMode(com.mcp.transfer.exposure.ToolExposureModes
                    .normalizeAssetAccessMode(server.getResourceAccessMode()));
        }
        mcpServerMapper.insertSelective(server);
        String serverKey = ServerKeyUtils.slug(server.getName());
        if (mcpServerMapper.selectByServerKey(serverKey) != null) {
            serverKey = ServerKeyUtils.withId(serverKey, server.getId());
        }
        mcpServerMapper.updateServerKey(server.getId(), serverKey);
        server.setServerKey(serverKey);
        recordOperation("CREATE", "SERVER", server.getId(), server.getName() + " " + server.getPath());
        refreshDynamicRoutes();
        return Result.success(server);
    }

    @Operation(summary = "更新 MCP Server")
    @PutMapping("/servers/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Result<McpServer> updateServer(@PathVariable Integer id, @RequestBody McpServer server) {
        if (!canManageServer(id)) {
            return Result.fail("无权限操作该 Server");
        }
        McpServer oldServer = mcpServerMapper.selectByPrimaryKey(id);
        if (oldServer == null) {
            return Result.fail("Server 不存在");
        }
        // 路径 / 上游地址 / 内部身份断言属于连接层变更，走 L2 审批
        if (isServerConnectionChange(oldServer, server)) {
            String approvalErr = denyDirect(ApprovalAction.SERVER_UPDATE_CONNECTION);
            if (approvalErr != null) {
                return Result.fail(428, approvalErr);
            }
        }
        return doUpdateServer(id, server);
    }

    /** 更新 Server 的实际逻辑（审批通过后在同一事务内复用） */
    public Result<McpServer> doUpdateServer(Integer id, McpServer server) {
        if (StringUtils.hasText(server.getPath())) {
            try {
                server.setPath(McpPathValidator.validateAndNormalize(server.getPath()));
            } catch (IllegalArgumentException e) {
                return Result.fail(e.getMessage());
            }
            McpServer samePath = mcpServerMapper.selectOneByPath(server.getPath());
            if (samePath != null && !id.equals(samePath.getId())) {
                return Result.fail("路径已被占用：" + server.getPath());
            }
        }
        if (StringUtils.hasText(server.getCapabilitiesJson())) {
            try {
                if (com.alibaba.fastjson.JSON.parseObject(server.getCapabilitiesJson()) == null) {
                    return Result.fail("capabilities_json 不是合法 JSON");
                }
            } catch (Exception e) {
                return Result.fail("capabilities_json 解析失败：" + e.getMessage());
            }
        }
        // 局部更新（例如只改 path）不应重置上游配置：仅当请求携带上游字段时才规范化
        if (server.getUpstreamType() != null || server.getUpstreamUrl() != null) {
            normalizeUpstream(server);
        }
        server.setId(id);
        server.setServerKey(null); // 稳定标识不可通过更新接口修改
        // 暴露策略必须走 /servers/{id}/tool-exposure（含热工具 code 校验与阈值校验）
        server.setToolAccessMode(null);
        server.setToolExposureMode(null);
        server.setPromptAccessMode(null);
        server.setResourceAccessMode(null);
        server.setHotToolCodes(null);
        server.setToolWarnThreshold(null);
        server.setToolMaxThreshold(null);
        server.setUpdateTime(new Date());
        mcpServerMapper.updateByPrimaryKeySelective(server);
        recordOperation("UPDATE", "SERVER", id, server.getName() + " " + server.getPath());
        refreshDynamicRoutes();
        return Result.success(mcpServerMapper.selectByPrimaryKey(id));
    }

    @Operation(summary = "删除 MCP Server")
    @DeleteMapping("/servers/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> deleteServer(@PathVariable Integer id) {
        if (!canManageServer(id)) {
            return Result.fail("无权限操作该 Server");
        }
        String approvalErr = denyDirect(ApprovalAction.SERVER_DELETE);
        if (approvalErr != null) {
            return Result.fail(428, approvalErr);
        }
        return doDeleteServer(id);
    }

    /** 删除 Server 的实际逻辑（审批通过后复用） */
    public Result<Void> doDeleteServer(Integer id) {
        // 先取归属租户，删除后无法再推导
        Integer tenantId = resolveTenantId("SERVER", id);
        // 级联清理：授权关系 → 审批单 → prompt/resource → tool → server
        approvalRequestMapper.deleteByServerId(id);
        mcpPromptMapper.deleteByServerId(id);
        mcpResourceMapper.deleteByServerId(id);
        mcpToolMapper.deleteByServerId(id);
        mcpServerMapper.deleteByPrimaryKey(id);
        recordOperation("DELETE", "SERVER", id, null, tenantId);
        // PURGE_SERVER：让数据面连带清掉该 server 的向量索引（控制面不再持有 embedding 凭证）
        configChangePublisher.toolChanged(id, null, "PURGE_SERVER");
        refreshDynamicRoutes();
        return Result.success();
    }

    // ============ 工具规模治理：预算 / 暴露模式 / 热工具 ============

    @Operation(summary = "全部 Server 的工具预算（管理台告警用）")
    @GetMapping("/servers/budgets")
    public Result<List<com.mcp.transfer.exposure.ToolBudget>> serverBudgets() {
        List<Integer> tenantIds = visibleTenantIds();
        List<McpServer> servers;
        if (tenantIds == null) {
            servers = mcpServerMapper.selectAll();
        } else if (tenantIds.isEmpty()) {
            servers = new ArrayList<>();
        } else {
            servers = mcpServerMapper.selectByTenantIds(tenantIds);
        }
        List<com.mcp.transfer.exposure.ToolBudget> budgets = new ArrayList<>();
        for (McpServer server : servers) {
            // 管理台按最坏情况（该应用可见全部工具）估算预算
            budgets.add(toolAccessPolicy.budget(server, null, true));
        }
        return Result.success(budgets);
    }

    @Operation(summary = "tools/list 分页埋点")
    @GetMapping("/tool-pagination-stats")
    public Result<Map<String, Object>> toolPaginationStats() {
        return Result.success(toolPaginationTelemetry.stats());
    }

    @Operation(summary = "重建 Server 工具向量索引")
    @PostMapping("/servers/{id}/embeddings/reindex")
    public Result<Map<String, Object>> reindexEmbeddings(@PathVariable Integer id) {
        if (!canManageServer(id)) {
            return Result.fail("无权限操作该 Server");
        }
        try {
            return Result.success(embeddingAdminPort.reindex(id));
        } catch (EmbeddingAdminPort.UnavailableException e) {
            return Result.fail(503, e.getMessage());
        }
    }

    @Operation(summary = "Embedding 索引状态")
    @GetMapping("/embeddings/stats")
    public Result<Map<String, Object>> embeddingStats() {
        try {
            return Result.success(embeddingAdminPort.stats());
        } catch (EmbeddingAdminPort.UnavailableException e) {
            return Result.success(java.util.Collections.singletonMap("enabled", false));
        }
    }

    @Operation(summary = "单个 Server 的工具预算（可指定应用视角）")
    @GetMapping("/servers/{id}/tool-budget")
    public Result<com.mcp.transfer.exposure.ToolBudget> serverToolBudget(@PathVariable Integer id) {
        if (!canManageServer(id)) {
            return Result.fail("无权限操作该 Server");
        }
        McpServer server = mcpServerMapper.selectByPrimaryKey(id);
        if (server == null) {
            return Result.fail("Server 不存在");
        }
        return Result.success(toolAccessPolicy.budget(server, null, true));
    }

    @Operation(summary = "设置 Server 工具访问/暴露模式与阈值")
    @PutMapping("/servers/{id}/tool-exposure")
    @Transactional(rollbackFor = Exception.class)
    public Result<McpServer> updateToolExposure(@PathVariable Integer id, @RequestBody ToolExposureRequest request) {
        if (!canManageServer(id)) {
            return Result.fail("无权限操作该 Server");
        }
        McpServer old = mcpServerMapper.selectByPrimaryKey(id);
        if (old == null) {
            return Result.fail("Server 不存在");
        }

        String accessMode = request.getToolAccessMode() == null
                ? com.mcp.transfer.exposure.ToolExposureModes.accessOf(old)
                : request.getToolAccessMode();
        if (!com.mcp.transfer.exposure.ToolExposureModes.isValidAccessMode(accessMode)) {
            return Result.fail("toolAccessMode 仅支持 WHITELIST / ALL");
        }
        String exposureMode = request.getToolExposureMode() == null
                ? com.mcp.transfer.exposure.ToolExposureModes.exposureOf(old)
                : request.getToolExposureMode();
        if (!com.mcp.transfer.exposure.ToolExposureModes.isValidExposureMode(exposureMode)) {
            return Result.fail("toolExposureMode 仅支持 DIRECT / META");
        }
        String promptMode = request.getPromptAccessMode() == null
                ? com.mcp.transfer.exposure.ToolExposureModes.promptAccessOf(old)
                : request.getPromptAccessMode();
        if (!com.mcp.transfer.exposure.ToolExposureModes.isValidAssetAccessMode(promptMode)) {
            return Result.fail("promptAccessMode 仅支持 WHITELIST / ALL");
        }
        String resourceMode = request.getResourceAccessMode() == null
                ? com.mcp.transfer.exposure.ToolExposureModes.resourceAccessOf(old)
                : request.getResourceAccessMode();
        if (!com.mcp.transfer.exposure.ToolExposureModes.isValidAssetAccessMode(resourceMode)) {
            return Result.fail("resourceAccessMode 仅支持 WHITELIST / ALL");
        }

        boolean hotProvided = request.getHotToolCodes() != null;
        List<String> hotCodes = hotProvided
                ? request.getHotToolCodes() : toolAccessPolicy.hotToolCodes(old);
        List<String> normalizedHot = new ArrayList<>();
        Map<String, McpTool> enabledByCode = new LinkedHashMap<>();
        for (McpTool tool : mcpToolMapper.selectAllByServerId(id)) {
            enabledByCode.put(tool.getCode(), tool);
        }
        for (String code : hotCodes) {
            if (!StringUtils.hasText(code)) {
                continue;
            }
            String trimmed = code.trim();
            if (enabledByCode.get(trimmed) == null) {
                if (hotProvided) {
                    return Result.fail("热工具不存在或未启用：" + trimmed);
                }
                // 未显式提交热工具时，自动丢弃已删除/禁用的历史配置，避免阻塞其他设置
                continue;
            }
            if (!normalizedHot.contains(trimmed)) {
                normalizedHot.add(trimmed);
            }
        }
        if (normalizedHot.size() > toolExposureProperties.getHotToolLimit()) {
            return Result.fail("热工具最多 " + toolExposureProperties.getHotToolLimit() + " 个");
        }

        Integer warn = request.getToolWarnThreshold() == null
                ? old.getToolWarnThreshold() : request.getToolWarnThreshold();
        Integer max = request.getToolMaxThreshold() == null
                ? old.getToolMaxThreshold() : request.getToolMaxThreshold();
        if (warn != null && warn <= 0) {
            warn = null;
        }
        if (max != null && max <= 0) {
            max = null;
        }
        int effectiveWarn = warn != null ? warn : toolExposureProperties.getWarnThreshold();
        int effectiveMax = max != null ? max : toolExposureProperties.getMaxThreshold();
        if (effectiveMax < effectiveWarn) {
            return Result.fail("硬阈值（" + effectiveMax + "）不能小于告警阈值（" + effectiveWarn + "）");
        }

        mcpServerMapper.updateToolExposure(id,
                com.mcp.transfer.exposure.ToolExposureModes.normalizeAccessMode(accessMode),
                com.mcp.transfer.exposure.ToolExposureModes.normalizeExposureMode(exposureMode),
                com.mcp.transfer.exposure.ToolExposureModes.normalizeAssetAccessMode(promptMode),
                com.mcp.transfer.exposure.ToolExposureModes.normalizeAssetAccessMode(resourceMode),
                com.alibaba.fastjson.JSON.toJSONString(normalizedHot),
                warn, max, new Date());
        recordOperation("UPDATE", "SERVER_TOOL_EXPOSURE", id,
                "access=" + accessMode + ", exposure=" + exposureMode
                        + ", promptAccess=" + promptMode + ", resourceAccess=" + resourceMode
                        + ", hot=" + normalizedHot.size(),
                old.getTenantId());
        // 暴露模式变化会影响 tools/list，通知已连接客户端重新拉取
        configChangePublisher.toolChanged(id);
        return Result.success(mcpServerMapper.selectByPrimaryKey(id));
    }

    @Operation(summary = "根据历史调用推荐 META 热工具")
    @GetMapping("/servers/{id}/hot-tools/recommend")
    public Result<Map<String, Object>> recommendHotTools(
            @PathVariable Integer id,
            @RequestParam(defaultValue = "30") Integer days,
            @RequestParam(defaultValue = "20") Integer limit) {
        if (!canManageServer(id)) {
            return Result.fail("无权限操作该 Server");
        }
        McpServer server = mcpServerMapper.selectByPrimaryKey(id);
        if (server == null) {
            return Result.fail("Server 不存在");
        }
        int windowDays = Math.min(Math.max(days == null ? 30 : days, 1), 365);
        int topN = Math.min(Math.max(limit == null ? 20 : limit, 1),
                Math.max(1, toolExposureProperties.getHotToolLimit()));
        Date since = new Date(System.currentTimeMillis() - windowDays * 86400000L);
        List<Map<String, Object>> rows = toolCallRecordMapper.topToolCodes(id, since, topN);

        Map<String, McpTool> enabled = new LinkedHashMap<>();
        for (McpTool tool : mcpToolMapper.selectAllByServerId(id)) {
            enabled.put(tool.getCode(), tool);
        }
        List<Map<String, Object>> tools = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        long totalCalls = 0;
        for (Map<String, Object> row : rows) {
            Object codeValue = row.get("methodCode");
            String code = codeValue == null ? null : String.valueOf(codeValue);
            if (!StringUtils.hasText(code) || enabled.get(code) == null) {
                continue;
            }
            long calls = row.get("calls") instanceof Number ? ((Number) row.get("calls")).longValue() : 0L;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", code);
            item.put("name", enabled.get(code).getName());
            item.put("calls", calls);
            item.put("score", calls);
            tools.add(item);
            codes.add(code);
            totalCalls += calls;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serverId", id);
        result.put("windowDays", windowDays);
        result.put("totalCalls", totalCalls);
        result.put("hotToolCodes", codes);
        result.put("tools", tools);
        if (tools.isEmpty()) {
            result.put("hint", "窗口内没有成功调用记录；可以先用 DIRECT 模式运行一段时间再推荐。");
        }
        return Result.success(result);
    }

    // ============ 工具 mcp_tool ============

    @Operation(summary = "工具列表")
    @GetMapping("/tools")
    public Result<List<McpTool>> listTools() {
        List<McpTool> tools;
        List<Integer> serverIds = visibleServerIds();
        if (serverIds == null) {
            tools = mcpToolMapper.selectAll();
        } else if (serverIds.isEmpty()) {
            tools = new ArrayList<>();
        } else {
            tools = mcpToolMapper.selectByServerIds(serverIds);
        }
        decryptTools(tools);
        return Result.success(tools);
    }
    private void decryptTools(List<McpTool> tools) {
        if (tools == null) {
            return;
        }
        for (McpTool t : tools) {
            t.setAuthConfig(secretCrypto.decrypt(t.getAuthConfig()));
        }
    }

    @Operation(summary = "创建工具")
    @PostMapping("/tools")
    public Result<McpTool> createTool(@RequestBody McpTool tool) {
        if (tool.getServerId() == null || !canManageServer(tool.getServerId())) {
            return Result.fail("无权限在该 Server 下创建工具");
        }
        String validateErr = validateToolForSave(tool, true);
        if (validateErr != null) {
            return Result.fail(validateErr);
        }
        Date now = new Date();
        tool.setCreateTime(now);
        tool.setUpdateTime(now);
        if (tool.getStatus() == null) {
            tool.setStatus(1);
        }
        if (tool.getNeedToken() == null) {
            tool.setNeedToken(0);
        }
        if (tool.getBodyType() == null) {
            tool.setBodyType(0);
        }
        if (tool.getType() == null) {
            tool.setType(0);
        }
        tool.setVersion(defaultVersion(tool.getVersion()));
        // auth_config 含鉴权凭证，加密后落库
        tool.setAuthConfig(secretCrypto.encrypt(tool.getAuthConfig()));
        applyTokenEstimate(tool);
        mcpToolMapper.insertSelective(tool);
        recordOperation("CREATE", "TOOL", tool.getId(), tool.getCode());
        onToolChanged(tool.getServerId(), tool.getId(), "UPSERT");
        tool.setAuthConfig(secretCrypto.decrypt(tool.getAuthConfig()));
        return Result.success(tool);
    }

    @Operation(summary = "更新工具")
    @PutMapping("/tools/{id}")
    public Result<McpTool> updateTool(@PathVariable Integer id, @RequestBody McpTool tool) {
        if (!canManageTool(id)) {
            return Result.fail("无权限操作该工具");
        }
        McpTool current = mcpToolMapper.selectByPrimaryKey(id);
        if (current == null) {
            return Result.fail("工具不存在");
        }
        // 下游凭证（auth_config）变更属于 L2：开发者需审批，管理员可直接执行
        if (tool.getAuthConfig() != null) {
            String oldConfig = secretCrypto.decrypt(current.getAuthConfig());
            if (!Objects.equals(tool.getAuthConfig(), oldConfig)) {
                String approvalErr = denyDirect(ApprovalAction.TOOL_CREDENTIAL_UPDATE);
                if (approvalErr != null) {
                    return Result.fail(428, approvalErr);
                }
            }
        }
        return doUpdateTool(id, tool);
    }

    /** 更新工具的实际逻辑（审批通过后复用） */
    public Result<McpTool> doUpdateTool(Integer id, McpTool tool) {
        if (!canManageTool(id)) {
            return Result.fail("无权限操作该工具");
        }
        boolean superAdmin = visibleTenantIds() == null;
        if (!superAdmin) {
            // 业务方管理员：不允许改 server 归属
            tool.setServerId(null);
        }
        McpTool oldTool = mcpToolMapper.selectByPrimaryKey(id);
        if (oldTool == null) {
            return Result.fail("工具不存在");
        }
        Integer oldServerId = oldTool.getServerId();
        tool.setId(id);
        tool.setUpdateTime(new Date());
        String validateErr = validateToolForSave(tool, false);
        if (validateErr != null) {
            return Result.fail(validateErr);
        }
        // 只有 resJson（工具能力定义）变化才升级版本，改名称/状态等不动版本
        boolean schemaChanged = tool.getResJson() != null && !tool.getResJson().equals(oldTool != null ? oldTool.getResJson() : null);
        if (schemaChanged) {
            String verErr = resolveUpdateVersion(oldTool != null ? oldTool.getVersion() : null, tool::setVersion, tool.getVersion());
            if (verErr != null) {
                return Result.fail(verErr);
            }
        } else {
            tool.setVersion(null);
        }
        tool.setAuthConfig(secretCrypto.encrypt(tool.getAuthConfig()));
        applyTokenEstimate(tool);
        mcpToolMapper.updateByPrimaryKeySelective(tool);
        recordOperation("UPDATE", "TOOL", id, tool.getCode());
        boolean serverMoved = tool.getServerId() != null && !tool.getServerId().equals(oldServerId);
        if (serverMoved) {
            // 原 server 的索引要删、新 server 的索引要加
            onToolChanged(oldServerId, id, "DELETE");
            onToolChanged(tool.getServerId(), id, "UPSERT");
        } else {
            onToolChanged(oldServerId, id, "UPSERT");
        }
        McpTool updated = mcpToolMapper.selectByPrimaryKey(id);
        updated.setAuthConfig(secretCrypto.decrypt(updated.getAuthConfig()));
        return Result.success(updated);
    }

    @Operation(summary = "删除工具")
    @DeleteMapping("/tools/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> deleteTool(@PathVariable Integer id) {
        if (!canManageTool(id)) {
            return Result.fail("无权限操作该工具");
        }
        McpTool tool = mcpToolMapper.selectByPrimaryKey(id);
        Integer serverId = tool != null ? tool.getServerId() : null;
        approvalRequestMapper.deleteByToolId(id);
        mcpToolMapper.deleteByPrimaryKey(id);
        recordOperation("DELETE", "TOOL", id, null, resolveTenantId("SERVER", serverId));
        onToolChanged(serverId, id, "DELETE");
        return Result.success();
    }

    /**
     * 批量迁移工具到另一个 Server（按业务域拆分 Server 的辅助接口）。
     * 迁移后工具级授权会被清除，需要在目标 Server 下重新授权。
     */
    @Operation(summary = "批量迁移工具到其他 Server")
    @PostMapping("/tools/bulk-move")
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> bulkMoveTools(@RequestBody BulkMoveToolsRequest request) {
        if (request == null || CollectionUtils.isEmpty(request.getToolIds()) || request.getTargetServerId() == null) {
            return Result.fail("toolIds 与 targetServerId 不能为空");
        }
        if (!canManageServer(request.getTargetServerId())) {
            return Result.fail("无权限操作目标 Server");
        }
        McpServer target = mcpServerMapper.selectByPrimaryKey(request.getTargetServerId());
        if (target == null) {
            return Result.fail("目标 Server 不存在");
        }
        if ("MCP".equalsIgnoreCase(target.getUpstreamType()) || "AGGREGATE".equalsIgnoreCase(target.getUpstreamType())) {
            return Result.fail("代理/聚合型 Server 不支持直接迁移本地工具");
        }

        List<McpTool> moving = new ArrayList<>();
        Map<String, McpTool> targetCodes = new LinkedHashMap<>();
        for (McpTool tool : mcpToolMapper.selectAllByServerId(target.getId())) {
            targetCodes.put(tool.getCode(), tool);
        }
        Set<Integer> seen = new HashSet<>();
        for (Integer toolId : request.getToolIds()) {
            if (toolId == null || !seen.add(toolId)) {
                continue;
            }
            McpTool tool = mcpToolMapper.selectByPrimaryKey(toolId);
            if (tool == null || tool.getServerId() == null) {
                return Result.fail("工具不存在: " + toolId);
            }
            if (!canManageServer(tool.getServerId())) {
                return Result.fail("无权限操作工具: " + toolId);
            }
            if (tool.getServerId().equals(target.getId())) {
                continue;
            }
            McpServer source = mcpServerMapper.selectByPrimaryKey(tool.getServerId());
            if (source == null || !Objects.equals(source.getTenantId(), target.getTenantId())) {
                return Result.fail("只能在同一业务方内迁移工具: " + tool.getCode());
            }
            McpTool conflict = targetCodes.get(tool.getCode());
            if (conflict != null && !conflict.getId().equals(tool.getId())) {
                return Result.fail("目标 Server 已存在同名工具: " + tool.getCode());
            }
            moving.add(tool);
        }
        if (moving.isEmpty()) {
            return Result.fail("没有需要迁移的工具（可能已在目标 Server）");
        }

        Set<Integer> sourceServerIds = new HashSet<>();
        Date now = new Date();
        for (McpTool tool : moving) {
            sourceServerIds.add(tool.getServerId());
            McpTool patch = new McpTool();
            patch.setId(tool.getId());
            patch.setServerId(target.getId());
            patch.setUpdateTime(now);
            mcpToolMapper.updateByPrimaryKeySelective(patch);
            targetCodes.put(tool.getCode(), tool);
        }
        for (Integer sourceId : sourceServerIds) {
            onToolChanged(sourceId);
        }
        onToolChanged(target.getId());
        recordOperation("MOVE", "TOOL", target.getId(),
                "迁移 " + moving.size() + " 个工具到 " + target.getName(),
                target.getTenantId());

        com.mcp.transfer.exposure.ToolBudget budget = toolAccessPolicy.budget(target, null, true);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("moved", moving.size());
        result.put("targetServerId", target.getId());
        result.put("toolCount", budget.getVisibleTools());
        result.put("overWarn", budget.isOverWarn());
        result.put("overMax", budget.isOverMax());
        if (budget.isOverWarn()) {
            result.put("warning", "目标 Server 工具数已达 " + budget.getVisibleTools()
                    + "（告警阈值 " + budget.getWarnThreshold() + "），建议启用 META 或继续拆分。");
        }
        return Result.success(result);
    }

    @Operation(summary = "重建全部 Tool / Prompt / Resource token 估算")
    @PostMapping("/tools/token-estimates/rebuild")
    public Result<Map<String, Object>> rebuildTokenEstimates() {
        List<Integer> serverIds = visibleServerIds();
        List<McpTool> tools = serverIds == null ? mcpToolMapper.selectAll()
                : (serverIds.isEmpty() ? new ArrayList<>() : mcpToolMapper.selectByServerIds(serverIds));
        int updated = 0;
        for (McpTool tool : tools) {
            applyTokenEstimate(tool);
            McpTool patch = new McpTool();
            patch.setId(tool.getId());
            patch.setTokenEstimate(tool.getTokenEstimate());
            patch.setTokenizer(tool.getTokenizer());
            patch.setUpdateTime(new Date());
            updated += mcpToolMapper.updateByPrimaryKeySelective(patch);
        }
        int promptUpdated = 0;
        for (McpPrompt prompt : mcpPromptMapper.selectAll()) {
            if (serverIds != null && !serverIds.contains(prompt.getServerId())) {
                continue;
            }
            applyAssetTokenEstimate(prompt);
            McpPrompt patch = new McpPrompt();
            patch.setId(prompt.getId());
            patch.setTokenEstimate(prompt.getTokenEstimate());
            patch.setTokenizer(prompt.getTokenizer());
            patch.setUpdateTime(new Date());
            promptUpdated += mcpPromptMapper.updateByPrimaryKeySelective(patch);
        }
        int resourceUpdated = 0;
        for (McpResource resource : mcpResourceMapper.selectAll()) {
            if (serverIds != null && !serverIds.contains(resource.getServerId())) {
                continue;
            }
            applyAssetTokenEstimate(resource);
            McpResource patch = new McpResource();
            patch.setId(resource.getId());
            patch.setTokenEstimate(resource.getTokenEstimate());
            patch.setTokenizer(resource.getTokenizer());
            patch.setUpdateTime(new Date());
            resourceUpdated += mcpResourceMapper.updateByPrimaryKeySelective(patch);
        }
        recordOperation("UPDATE", "TOKEN_ESTIMATE", null,
                "重算 tool=" + updated + ", prompt=" + promptUpdated + ", resource=" + resourceUpdated);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updated", updated);
        result.put("promptUpdated", promptUpdated);
        result.put("resourceUpdated", resourceUpdated);
        result.put("tokenizer", tokenEstimator.method());
        return Result.success(result);
    }

    /** 工具注册前连通性测试（本地格式校验 / HTTP 实际试调） */
    @Operation(summary = "测试工具连通性")
    @PostMapping("/tools/test-connect")
    public Result<Map<String, Object>> testToolConnect(@RequestBody McpTool tool, javax.servlet.http.HttpServletRequest request) {
        String validateErr = validateToolForSave(tool, false);
        if (validateErr != null) {
            Map<String, Object> invalid = new HashMap<>();
            invalid.put("ok", false);
            invalid.put("error", validateErr);
            return Result.success(invalid);
        }
        return Result.success(toolConnectivityProbe.test(tool, request.getHeader("Authorization")));
    }

    /** REST-to-MCP：从 REST 请求示例（URL/方法/body）自动生成工具草稿（inputSchema + paramMapping） */
    @Operation(summary = "从 REST 生成工具草稿")
    @PostMapping("/tools/from-rest")
    public Result<Map<String, Object>> fromRest(@RequestBody Map<String, Object> body) {
        String url = bodyString(body, "url");
        if (!StringUtils.hasText(url)) {
            return Result.fail("url 不能为空");
        }
        try {
            return Result.success(RestToolDraftBuilder.buildDraft(
                    bodyString(body, "name"),
                    bodyString(body, "code"),
                    bodyString(body, "description"),
                    bodyString(body, "method"),
                    url,
                    bodyString(body, "bodyExample"),
                    bodyString(body, "authType")));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    private static String bodyString(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Integer bodyInt(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> bodyStringList(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (!(v instanceof List)) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (Object o : (List<?>) v) {
            if (o != null) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    /** OpenAPI 文档上限（前端上传后以 JSON 字符串提交） */
    private static final int MAX_OPENAPI_DOCUMENT_LENGTH = 5 * 1024 * 1024;

    /** OpenAPI 导入预览：解析 + 前置校验，返回每个 operation 的工具草稿（不落库） */
    @Operation(summary = "OpenAPI 导入预览")
    @PostMapping("/tools/openapi/preview")
    public Result<OpenApiImportPlan> previewOpenApiTools(@RequestBody Map<String, Object> body) {
        Integer serverId = bodyInt(body, "serverId");
        if (serverId == null || !canManageServer(serverId)) {
            return Result.fail("无权限操作该 Server");
        }
        String document = bodyString(body, "document");
        if (!StringUtils.hasText(document)) {
            return Result.fail("document 不能为空");
        }
        if (document.length() > MAX_OPENAPI_DOCUMENT_LENGTH) {
            return Result.fail("document 过大（上限 5MB）");
        }
        Map<String, McpTool> existing = existingToolsByCode(serverId);
        OpenApiImportPlan plan = OpenApiToolParser.parse(document, bodyString(body, "baseUrl"), existing.keySet());
        warnSsrfBlockedBaseUrl(plan);
        return Result.success(plan);
    }

    /** 按 OpenAPI 批量导入工具（默认跳过冲突；OVERWRITE 时覆盖已存在的同 code 工具） */
    @Operation(summary = "按 OpenAPI 批量导入工具")
    @PostMapping("/tools/openapi/import")
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> importOpenApiTools(@RequestBody Map<String, Object> body) {
        Integer serverId = bodyInt(body, "serverId");
        if (serverId == null || !canManageServer(serverId)) {
            return Result.fail("无权限操作该 Server");
        }
        String document = bodyString(body, "document");
        if (!StringUtils.hasText(document)) {
            return Result.fail("document 不能为空");
        }
        if (document.length() > MAX_OPENAPI_DOCUMENT_LENGTH) {
            return Result.fail("document 过大（上限 5MB）");
        }
        String policyInput = bodyString(body, "conflictPolicy");
        String policy = StringUtils.hasText(policyInput) ? policyInput.trim().toUpperCase() : "SKIP";
        if (!"SKIP".equals(policy) && !"OVERWRITE".equals(policy)) {
            return Result.fail("conflictPolicy 仅支持 SKIP / OVERWRITE");
        }
        List<String> selectedCodes = bodyStringList(body, "codes");

        Map<String, McpTool> existing = existingToolsByCode(serverId);
        OpenApiImportPlan plan = OpenApiToolParser.parse(document, bodyString(body, "baseUrl"), existing.keySet());
        warnSsrfBlockedBaseUrl(plan);

        int created = 0;
        int updated = 0;
        int skipped = 0;
        List<Map<String, Object>> failures = new ArrayList<>();
        Date now = new Date();

        for (OpenApiToolDraft draft : plan.getTools()) {
            if (selectedCodes != null && !selectedCodes.contains(draft.getCode())) {
                continue;
            }
            if (OpenApiToolDraft.ERROR.equals(draft.getStatus())) {
                failures.add(importFailure(draft.getCode(), firstErrorMessage(draft)));
                continue;
            }
            McpTool old = existing.get(draft.getCode());
            if (old != null && "SKIP".equals(policy)) {
                skipped++;
                continue;
            }
            String resJson = buildToolResJson(draft);
            McpTool tool = new McpTool();
            tool.setServerId(serverId);
            tool.setCode(draft.getCode());
            tool.setName(draft.getName());
            tool.setType(1);
            tool.setStatus(1);
            tool.setServiceUrl(draft.getServiceUrl());
            tool.setRequestType(draft.getMethod());
            tool.setNeedToken(0);
            tool.setBodyType(0);
            tool.setAuthType(draft.getAuthType());
            String authConfig = draft.getAuthConfig() == null || draft.getAuthConfig().isEmpty()
                    ? null : draft.getAuthConfig().toJSONString();
            tool.setAuthConfig(authConfig);
            tool.setParamMapping(draft.getParamMapping().toJSONString());
            tool.setResJson(resJson);
            tool.setTags(StringUtils.hasText(draft.getTags()) ? draft.getTags() : null);
            tool.setVersion(draft.getVersion());

            applyTokenEstimate(tool);
            if (old != null) {
                // 覆盖模式：已有下游凭证不因草稿为空被清空；凭证变化仍走审批矩阵
                String oldConfig = secretCrypto.decrypt(old.getAuthConfig());
                boolean sameAuthType = Objects.equals(draft.getAuthType(), old.getAuthType());
                if (!StringUtils.hasText(authConfig) && sameAuthType) {
                    tool.setAuthConfig(null);
                } else if (!Objects.equals(authConfig, oldConfig)) {
                    String approvalErr = denyDirect(ApprovalAction.TOOL_CREDENTIAL_UPDATE);
                    if (approvalErr != null) {
                        failures.add(importFailure(draft.getCode(), "下游凭证变更需审批：" + approvalErr));
                        continue;
                    }
                }
                tool.setId(old.getId());
                tool.setUpdateTime(now);
                String err = validateToolForSave(tool, false);
                if (err != null) {
                    failures.add(importFailure(draft.getCode(), err));
                    continue;
                }
                if (StringUtils.hasText(tool.getAuthConfig())) {
                    tool.setAuthConfig(secretCrypto.encrypt(tool.getAuthConfig()));
                }
                tool.setVersion(nextToolVersion(old.getVersion(), draft.getVersion(), resJson.equals(old.getResJson())));
                mcpToolMapper.updateByPrimaryKeySelective(tool);
                updated++;
            } else {
                tool.setCreateTime(now);
                tool.setUpdateTime(now);
                tool.setVersion(defaultVersion(draft.getVersion()));
                String err = validateToolForSave(tool, true);
                if (err != null) {
                    failures.add(importFailure(draft.getCode(), err));
                    continue;
                }
                if (StringUtils.hasText(tool.getAuthConfig())) {
                    tool.setAuthConfig(secretCrypto.encrypt(tool.getAuthConfig()));
                }
                mcpToolMapper.insertSelective(tool);
                created++;
            }
        }

        if (created + updated > 0) {
            // 批量导入不带 toolId，数据面按「整 server 重建索引」处理
            onToolChanged(serverId);
        }
        recordOperation("IMPORT", "TOOL", null,
                "OpenAPI 导入：新增 " + created + "，更新 " + updated + "，跳过 " + skipped + "，失败 " + failures.size(),
                resolveTenantId("SERVER", serverId));

        Map<String, Object> result = new HashMap<>();
        result.put("created", created);
        result.put("updated", updated);
        result.put("skipped", skipped);
        result.put("failed", failures.size());
        result.put("failures", failures);
        result.put("baseUrl", plan.getBaseUrl());

        // 导入护栏：一次性导入过多工具时给出明确告警（不阻断，避免破坏既有批处理）
        McpServer importedServer = mcpServerMapper.selectByPrimaryKey(serverId);
        if (importedServer != null) {
            com.mcp.transfer.exposure.ToolBudget budget =
                    toolAccessPolicy.budget(importedServer, null, true);
            result.put("toolCount", budget.getVisibleTools());
            result.put("warnThreshold", budget.getWarnThreshold());
            result.put("maxThreshold", budget.getMaxThreshold());
            result.put("overWarn", budget.isOverWarn());
            result.put("overMax", budget.isOverMax());
            if (budget.isOverWarn()) {
                result.put("warning", "该 Server 工具数已达 " + budget.getVisibleTools()
                        + "（告警阈值 " + budget.getWarnThreshold() + "）。建议启用 META 暴露模式、"
                        + "收紧应用白名单，或按业务域拆分 Server，避免客户端 tools/list 上下文过大。");
            }
        }
        return Result.success(result);
    }

    private Map<String, McpTool> existingToolsByCode(Integer serverId) {
        Map<String, McpTool> map = new HashMap<>();
        List<McpTool> tools = mcpToolMapper.selectByServerIds(java.util.Collections.singletonList(serverId));
        if (tools != null) {
            for (McpTool t : tools) {
                if (t.getCode() != null) {
                    map.put(t.getCode(), t);
                }
            }
        }
        return map;
    }

    private String buildToolResJson(OpenApiToolDraft draft) {
        com.alibaba.fastjson.JSONObject resJson = new com.alibaba.fastjson.JSONObject();
        resJson.put("name", draft.getCode());
        resJson.put("description", draft.getDescription() == null ? "" : draft.getDescription());
        resJson.put("inputSchema", draft.getInputSchema());
        return resJson.toJSONString();
    }

    private String nextToolVersion(String current, String requested, boolean unchanged) {
        String cur = StringUtils.hasText(current) ? current : DEFAULT_VERSION;
        if (unchanged) {
            return cur;
        }
        if (StringUtils.hasText(requested) && compareVersion(requested, cur) > 0) {
            return requested;
        }
        return incrementPatch(cur);
    }

    private Map<String, Object> importFailure(String code, String message) {
        Map<String, Object> failure = new HashMap<>();
        failure.put("code", code);
        failure.put("message", message);
        return failure;
    }

    private String firstErrorMessage(OpenApiToolDraft draft) {
        for (OpenApiIssue issue : draft.getIssues()) {
            if (OpenApiIssue.ERROR.equals(issue.getLevel())) {
                return issue.getMessage();
            }
        }
        return "校验未通过";
    }

    /** baseUrl 若命中 SSRF 策略，提示导入后调用会被拦截（不阻塞导入，便于先配置再放开） */
    private void warnSsrfBlockedBaseUrl(OpenApiImportPlan plan) {
        if (!StringUtils.hasText(plan.getBaseUrl())) {
            return;
        }
        try {
            urlSecurityValidator.validate(plan.getBaseUrl());
        } catch (SecurityException e) {
            plan.addIssue(OpenApiIssue.warn("document",
                    "baseUrl 会被网关 SSRF 策略拦截（" + e.getMessage()
                            + "）；如需访问内网/本机地址，请设置 MCP_SECURITY_ALLOW_PRIVATE_NETWORK=true 后重启网关"));
        }
    }

    /** 创建时默认版本号 */
    private static final String DEFAULT_VERSION = "1.0.0";

    private String defaultVersion(String version) {
        return StringUtils.hasText(version) ? version : DEFAULT_VERSION;
    }

    /** 解析版本号里的一段数字（非数字按 0） */
    private int parseIntSegment(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 语义化版本比较：v1 > v2 返回正数，相等 0，小于负数 */
    private int compareVersion(String v1, String v2) {
        String[] a = v1.split("\\.");
        String[] b = v2.split("\\.");
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? parseIntSegment(a[i]) : 0;
            int y = i < b.length ? parseIntSegment(b[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    /** patch 位自动 +1：1.0.0 → 1.0.1 */
    private String incrementPatch(String version) {
        String v = StringUtils.hasText(version) ? version : DEFAULT_VERSION;
        String[] parts = v.split("\\.");
        if (parts.length >= 3) {
            parts[2] = String.valueOf(parseIntSegment(parts[2]) + 1);
            return String.join(".", parts);
        }
        if (parts.length == 2) {
            return parts[0] + "." + parts[1] + ".1";
        }
        return DEFAULT_VERSION;
    }

    /**
     * 更新时解析版本号：
     * - 不传 version，或传的与当前相同（前端回显未改）→ 自动按当前版本 patch+1；
     * - 传了不同的 version → 必须大于当前版本，否则返回错误。
     *
     * @return 错误信息，null 表示通过（并已把最终版本写回 target）
     */
    private String resolveUpdateVersion(String currentVersion, java.util.function.Consumer<String> setter, String inputVersion) {
        String cur = StringUtils.hasText(currentVersion) ? currentVersion : DEFAULT_VERSION;
        if (StringUtils.hasText(inputVersion) && !inputVersion.equals(cur)) {
            if (compareVersion(inputVersion, cur) <= 0) {
                return "版本号必须大于当前版本 " + cur;
            }
            setter.accept(inputVersion);
        } else {
            setter.accept(incrementPatch(cur));
        }
        return null;
    }

    /** 工具静态校验：resJson/inputSchema、serviceUrl 格式、authConfig JSON 合法性 */
    private void applyAssetTokenEstimate(McpPrompt prompt) {
        if (prompt == null || prompt.getResJson() == null) {
            return;
        }
        prompt.setTokenEstimate(tokenEstimator.estimate(prompt.getResJson()) + 30);
        prompt.setTokenizer(tokenEstimator.method());
    }

    private void applyAssetTokenEstimate(McpResource resource) {
        if (resource == null || resource.getResJson() == null) {
            return;
        }
        resource.setTokenEstimate(tokenEstimator.estimate(resource.getResJson()) + 30);
        resource.setTokenizer(tokenEstimator.method());
    }

    private void applyTokenEstimate(McpTool tool) {
        if (tool == null || !StringUtils.hasText(tool.getResJson())) {
            return;
        }
        tool.setTokenEstimate(tokenEstimator.estimate(tool.getResJson()) + 30);
        tool.setTokenizer(tokenEstimator.method());
    }

    private String validateToolForSave(McpTool tool, boolean isCreate) {
        if (isCreate) {
            if (!StringUtils.hasText(tool.getCode())) {
                return "工具标识 code 不能为空";
            }
            if (tool.getServerId() == null) {
                return "归属 Server 不能为空";
            }
        }
        // META 模式的三个元工具使用保留名，避免真实工具被遮蔽
        if (StringUtils.hasText(tool.getCode())
                && com.mcp.transfer.exposure.MetaToolFactory.isMetaTool(tool.getCode())) {
            return "工具标识 " + tool.getCode() + " 是 META 模式保留名，请更换";
        }
        // resJson：必须是合法 JSON，且 inputSchema.type 为 object
        if (StringUtils.hasText(tool.getResJson())) {
            try {
                com.alibaba.fastjson.JSONObject resJson = com.alibaba.fastjson.JSON.parseObject(tool.getResJson());
                if (resJson == null) {
                    return "resJson 不是合法 JSON";
                }
                com.alibaba.fastjson.JSONObject inputSchema = resJson.getJSONObject("inputSchema");
                if (inputSchema != null && !"object".equals(inputSchema.getString("type"))) {
                    return "inputSchema.type 必须为 object";
                }
            } catch (Exception e) {
                return "resJson 解析失败：" + e.getMessage();
            }
        }
        // type=1（HTTP 转发）：serviceUrl 需 http(s)，authConfig 需合法 JSON
        if (tool.getType() != null && tool.getType() == 1) {
            if (StringUtils.hasText(tool.getServiceUrl())
                    && !tool.getServiceUrl().startsWith("http://") && !tool.getServiceUrl().startsWith("https://")) {
                return "HTTP 转发工具 serviceUrl 需以 http:// 或 https:// 开头";
            }
            if (StringUtils.hasText(tool.getAuthConfig())) {
                try {
                    if (com.alibaba.fastjson.JSON.parseObject(tool.getAuthConfig()) == null) {
                        return "authConfig 不是合法 JSON";
                    }
                } catch (Exception e) {
                    return "authConfig 解析失败：" + e.getMessage();
                }
            }
        }
        return null;
    }

    // ============ Prompt ============

    @Operation(summary = "Prompt 列表")
    @GetMapping("/prompts")
    public Result<List<McpPrompt>> listPrompts() {
        List<Integer> serverIds = visibleServerIds();
        if (serverIds == null) {
            return Result.success(mcpPromptMapper.selectAll());
        }
        if (serverIds.isEmpty()) {
            return Result.success(new ArrayList<>());
        }
        return Result.success(mcpPromptMapper.selectByServerIds(serverIds));
    }

    @Operation(summary = "创建 Prompt")
    @PostMapping("/prompts")
    public Result<McpPrompt> createPrompt(@RequestBody McpPrompt prompt) {
        if (prompt.getServerId() == null || !canManageServer(prompt.getServerId())) {
            return Result.fail("无权限在该 Server 下创建 Prompt");
        }
        Date now = new Date();
        prompt.setCreateTime(now);
        prompt.setUpdateTime(now);
        if (prompt.getStatus() == null) {
            prompt.setStatus(1);
        }
        prompt.setVersion(defaultVersion(prompt.getVersion()));
        applyAssetTokenEstimate(prompt);
        mcpPromptMapper.insertSelective(prompt);
        configChangePublisher.promptChanged(prompt.getServerId());
        recordOperation("CREATE", "PROMPT", prompt.getId(), prompt.getName());
        return Result.success(prompt);
    }

    @Operation(summary = "更新 Prompt")
    @PutMapping("/prompts/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Result<McpPrompt> updatePrompt(@PathVariable Integer id, @RequestBody McpPrompt prompt) {
        McpPrompt oldPrompt = mcpPromptMapper.selectByPrimaryKey(id);
        if (oldPrompt == null) {
            return Result.fail("Prompt 不存在");
        }
        // 权限必须基于「数据库中的归属」，不能信任请求体里的 serverId
        if (!canManageServer(oldPrompt.getServerId())) {
            return Result.fail("无权限操作该 Prompt");
        }
        if (prompt.getServerId() != null && !prompt.getServerId().equals(oldPrompt.getServerId())) {
            // 只有超管允许迁移归属，且目标 server 也必须可管理
            if (visibleTenantIds() != null || !canManageServer(prompt.getServerId())) {
                return Result.fail("无权限修改 Prompt 归属");
            }
        }
        boolean schemaChanged = prompt.getResJson() != null && !prompt.getResJson().equals(oldPrompt.getResJson());
        if (schemaChanged) {
            String verErr = resolveUpdateVersion(oldPrompt.getVersion(), prompt::setVersion, prompt.getVersion());
            if (verErr != null) {
                return Result.fail(verErr);
            }
        } else {
            prompt.setVersion(null);
        }
        prompt.setId(id);
        prompt.setUpdateTime(new Date());
        if (prompt.getResJson() != null) {
            applyAssetTokenEstimate(prompt);
        }
        mcpPromptMapper.updateByPrimaryKeySelective(prompt);
        configChangePublisher.promptChanged(oldPrompt.getServerId());
        recordOperation("UPDATE", "PROMPT", id, prompt.getName(), oldPrompt.getServerId() == null ? null : resolveTenantId("SERVER", oldPrompt.getServerId()));
        return Result.success(mcpPromptMapper.selectByPrimaryKey(id));
    }

    @Operation(summary = "删除 Prompt")
    @DeleteMapping("/prompts/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> deletePrompt(@PathVariable Integer id) {
        McpPrompt prompt = mcpPromptMapper.selectByPrimaryKey(id);
        if (prompt == null || !canManageServer(prompt.getServerId())) {
            return Result.fail("无权限操作该 Prompt");
        }
        approvalRequestMapper.deleteByPromptId(id);
        mcpPromptMapper.deleteByPrimaryKey(id);
        configChangePublisher.promptChanged(prompt.getServerId());
        recordOperation("DELETE", "PROMPT", id, null, resolveTenantId("SERVER", prompt.getServerId()));
        return Result.success();
    }

    // ============ Resource ============

    @Operation(summary = "Resource 列表")
    @GetMapping("/resources")
    public Result<List<McpResource>> listResources() {
        List<Integer> serverIds = visibleServerIds();
        if (serverIds == null) {
            return Result.success(mcpResourceMapper.selectAll());
        }
        if (serverIds.isEmpty()) {
            return Result.success(new ArrayList<>());
        }
        return Result.success(mcpResourceMapper.selectByServerIds(serverIds));
    }

    @Operation(summary = "创建 Resource")
    @PostMapping("/resources")
    public Result<McpResource> createResource(@RequestBody McpResource resource) {
        if (resource.getServerId() == null || !canManageServer(resource.getServerId())) {
            return Result.fail("无权限在该 Server 下创建 Resource");
        }
        Date now = new Date();
        resource.setCreateTime(now);
        resource.setUpdateTime(now);
        if (resource.getStatus() == null) {
            resource.setStatus(1);
        }
        resource.setVersion(defaultVersion(resource.getVersion()));
        applyAssetTokenEstimate(resource);
        mcpResourceMapper.insertSelective(resource);
        configChangePublisher.resourceChanged(resource.getServerId());
        recordOperation("CREATE", "RESOURCE", resource.getId(), resource.getUri());
        return Result.success(resource);
    }

    @Operation(summary = "更新 Resource")
    @PutMapping("/resources/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Result<McpResource> updateResource(@PathVariable Integer id, @RequestBody McpResource resource) {
        McpResource oldResource = mcpResourceMapper.selectByPrimaryKey(id);
        if (oldResource == null) {
            return Result.fail("Resource 不存在");
        }
        if (!canManageServer(oldResource.getServerId())) {
            return Result.fail("无权限操作该 Resource");
        }
        if (resource.getServerId() != null && !resource.getServerId().equals(oldResource.getServerId())) {
            if (visibleTenantIds() != null || !canManageServer(resource.getServerId())) {
                return Result.fail("无权限修改 Resource 归属");
            }
        }
        boolean schemaChanged = resource.getResJson() != null && !resource.getResJson().equals(oldResource.getResJson());
        if (schemaChanged) {
            String verErr = resolveUpdateVersion(oldResource.getVersion(), resource::setVersion, resource.getVersion());
            if (verErr != null) {
                return Result.fail(verErr);
            }
        } else {
            resource.setVersion(null);
        }
        resource.setId(id);
        resource.setUpdateTime(new Date());
        if (resource.getResJson() != null) {
            applyAssetTokenEstimate(resource);
        }
        mcpResourceMapper.updateByPrimaryKeySelective(resource);
        configChangePublisher.resourceChanged(oldResource.getServerId());
        recordOperation("UPDATE", "RESOURCE", id, resource.getUri(), resolveTenantId("SERVER", oldResource.getServerId()));
        return Result.success(mcpResourceMapper.selectByPrimaryKey(id));
    }

    @Operation(summary = "删除 Resource")
    @DeleteMapping("/resources/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> deleteResource(@PathVariable Integer id) {
        McpResource resource = mcpResourceMapper.selectByPrimaryKey(id);
        if (resource == null || !canManageServer(resource.getServerId())) {
            return Result.fail("无权限操作该 Resource");
        }
        approvalRequestMapper.deleteByResourceId(id);
        mcpResourceMapper.deleteByPrimaryKey(id);
        configChangePublisher.resourceChanged(resource.getServerId());
        recordOperation("DELETE", "RESOURCE", id, null, resolveTenantId("SERVER", resource.getServerId()));
        return Result.success();
    }

    // ============ 应用 enn_app_info ============

    @Operation(summary = "应用列表")
    @GetMapping("/service-systems")
    public Result<List<ServiceSystem>> listServiceSystems() {
        return Result.success(serviceSystemMapper.selectAll());
    }

    @Operation(summary = "更新下游系统")
    @PutMapping("/service-systems/{id}")
    public Result<ServiceSystem> updateServiceSystem(@PathVariable Integer id, @RequestBody ServiceSystem serviceSystem) {
        if (serviceSystemMapper.selectByPrimaryKey(id) == null) {
            return Result.fail("下游系统不存在");
        }
        if (StringUtils.hasText(serviceSystem.getAuthUrl())
                && !serviceSystem.getAuthUrl().startsWith("http://")
                && !serviceSystem.getAuthUrl().startsWith("https://")) {
            return Result.fail("authUrl 需以 http:// 或 https:// 开头");
        }
        serviceSystem.setId(id);
        serviceSystem.setUpdateTime(new Date());
        serviceSystemMapper.updateByPrimaryKeySelective(serviceSystem);
        return Result.success(serviceSystemMapper.selectByPrimaryKey(id));
    }

    // ============ 工具调用记录 tool_call_record ============

    @Operation(summary = "工具调用记录")
    @GetMapping("/tool-call-records")
    public Result<PageResult<ToolCallRecord>> toolCallRecords(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date startTime,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date endTime,
            @RequestParam(required = false) Integer success,
            @RequestParam(required = false) Integer serverId,
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) String userId) {
        int start = (Math.max(pageNum, 1) - 1) * clampPageSize(pageSize);
        pageSize = clampPageSize(pageSize);
        List<Integer> tenantIds = visibleTenantIds();
        if (tenantIds != null && tenantIds.isEmpty()) {
            return Result.success(new PageResult<>(new ArrayList<>(), 0, pageNum, pageSize));
        }
        List<ToolCallRecord> list = toolCallRecordMapper.selectWithFilter(start, pageSize, startTime, endTime, success, serverId, appId, userId, tenantIds);
        long total = toolCallRecordMapper.countWithFilter(startTime, endTime, success, serverId, appId, userId, tenantIds);
        return Result.success(new PageResult<>(list, total, pageNum, pageSize));
    }

    @Operation(summary = "工具调用统计")
    @GetMapping("/tool-call-stats")
    public Result<Map<String, Object>> toolCallStats() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return Result.success(toolCallRecordMapper.stats(cal.getTime(), visibleTenantIds()));
    }

    // ============ 概览聚合 dashboard ============

    @Operation(summary = "概览统计")
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        Map<String, Object> data = new HashMap<>();
        List<Integer> tenantIds = visibleTenantIds();

        Calendar todayCal = Calendar.getInstance();
        todayCal.set(Calendar.HOUR_OF_DAY, 0);
        todayCal.set(Calendar.MINUTE, 0);
        todayCal.set(Calendar.SECOND, 0);
        todayCal.set(Calendar.MILLISECOND, 0);
        Date todayStart = todayCal.getTime();

        Calendar yesterdayCal = Calendar.getInstance();
        yesterdayCal.add(Calendar.DAY_OF_YEAR, -1);
        yesterdayCal.set(Calendar.HOUR_OF_DAY, 0);
        yesterdayCal.set(Calendar.MINUTE, 0);
        yesterdayCal.set(Calendar.SECOND, 0);
        yesterdayCal.set(Calendar.MILLISECOND, 0);
        Date yesterdayStart = yesterdayCal.getTime();

        Map<String, Object> toolStats = toolCallRecordMapper.stats(todayStart, tenantIds);
        long totalRequests = toolCallRecordMapper.countAll(null, tenantIds);
        long todayRequests = toLong(toolStats.get("today"));
        long yesterdayRequests = toolCallRecordMapper.countSince(yesterdayStart, tenantIds) - todayRequests;

        List<Tenant> visibleTenants = tenantMapper.selectAll();
        if (tenantIds != null) {
            visibleTenants = visibleTenants.stream().filter(t -> tenantIds.contains(t.getId())).collect(Collectors.toList());
        }
        List<McpServer> visibleServers = tenantIds == null ? mcpServerMapper.selectAll() : mcpServerMapper.selectByTenantIds(tenantIds);
        List<McpTool> visibleTools = mcpToolMapper.selectAll();
        if (tenantIds != null) {
            List<Integer> serverIds = visibleServers.stream().map(McpServer::getId).collect(Collectors.toList());
            visibleTools = serverIds.isEmpty() ? new ArrayList<>() : mcpToolMapper.selectByServerIds(serverIds);
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalRequests", totalRequests);
        stats.put("toolCalls", toLong(toolStats.get("total")));
        stats.put("successCount", toLong(toolStats.get("successCount")));
        stats.put("failCount", toLong(toolStats.get("failCount")));
        stats.put("avgDuration", toLong(toolStats.get("avgDuration")));
        stats.put("tenantCount", visibleTenants.size());
        stats.put("serverCount", visibleServers.size());
        stats.put("toolCount", visibleTools.size());
        stats.put("activeTenantCount", (int) visibleTenants.stream().filter(t -> t.getStatus() != null && t.getStatus() == 1).count());
        stats.put("todayRequests", todayRequests);
        stats.put("todayRequestsDelta", yesterdayRequests > 0
                ? Math.round((todayRequests - yesterdayRequests) * 100.0 / yesterdayRequests)
                : 0L);
        // 工具预算告警：按最坏情况（应用可见全量）估算，供首页提示
        int overBudgetCount = 0;
        List<Map<String, Object>> budgetWarnings = new ArrayList<>();
        for (McpServer server : visibleServers) {
            com.mcp.transfer.exposure.ToolBudget budget = toolAccessPolicy.budget(server, null, true);
            if (budget.isOverWarn()) {
                overBudgetCount++;
                Map<String, Object> warning = new LinkedHashMap<>();
                warning.put("serverId", server.getId());
                warning.put("serverName", server.getName());
                warning.put("path", server.getPath());
                warning.put("toolCount", budget.getVisibleTools());
                warning.put("warnThreshold", budget.getWarnThreshold());
                warning.put("overMax", budget.isOverMax());
                warning.put("exposureMode", budget.getExposureMode());
                budgetWarnings.add(warning);
            }
        }
        stats.put("overToolBudgetCount", overBudgetCount);
        data.put("toolBudgetWarnings", budgetWarnings);
        data.put("stats", stats);

        // 近 14 天趋势
        Calendar trendStart = Calendar.getInstance();
        trendStart.add(Calendar.DAY_OF_YEAR, -13);
        trendStart.set(Calendar.HOUR_OF_DAY, 0);
        trendStart.set(Calendar.MINUTE, 0);
        trendStart.set(Calendar.SECOND, 0);
        trendStart.set(Calendar.MILLISECOND, 0);
        data.put("trend", toolCallRecordMapper.trendSince(trendStart.getTime(), tenantIds));

        data.put("toolDist", toolCallRecordMapper.countByMethod(tenantIds));
        data.put("callTypeDist", toolCallRecordMapper.countByCallType(tenantIds));

        return Result.success(data);
    }

    // ============ 操作审计日志 operation_log ============

    @Operation(summary = "操作审计日志")
    @GetMapping("/operation-logs")
    public Result<PageResult<OperationLog>> operationLogs(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String username) {
        pageSize = clampPageSize(pageSize);
        int start = (Math.max(pageNum, 1) - 1) * pageSize;
        List<Integer> tenantIds = visibleTenantIds();
        if (tenantIds != null && tenantIds.isEmpty()) {
            return Result.success(new PageResult<>(new ArrayList<>(), 0, pageNum, pageSize));
        }
        List<OperationLog> list = operationLogMapper.selectWithPage(start, pageSize, action, username, tenantIds);
        long total = operationLogMapper.count(action, username, tenantIds);
        return Result.success(new PageResult<>(list, total, pageNum, pageSize));
    }

    /** 记录操作审计日志（自动推导租户） */
    private void recordOperation(String action, String resourceType, Object resourceId, Object detail) {
        recordOperation(action, resourceType, resourceId, detail, resolveTenantId(resourceType, resourceId));
    }

    /** 记录操作审计日志（显式指定租户） */
    private void recordOperation(String action, String resourceType, Object resourceId, Object detail, Integer tenantId) {
        try {
            AdminAuthContext ctx = AdminAuthContext.get();
            OperationLog log = new OperationLog();
            log.setUsername(ctx != null ? ctx.getUsername() : null);
            log.setTenantId(tenantId);
            log.setAction(action);
            log.setResourceType(resourceType);
            log.setResourceId(resourceId != null ? String.valueOf(resourceId) : null);
            log.setDetail(detail != null ? String.valueOf(detail) : null);
            log.setCreateTime(new Date());
            operationLogMapper.insertSelective(log);
        } catch (Exception e) {
            log.error("[AUDIT] 记录操作日志失败", e);
        }
    }

    /** 根据资源类型/ID 推导归属租户（用于审计与审批隔离） */
    private Integer resolveTenantId(String resourceType, Object resourceId) {
        try {
            if (resourceType == null || resourceId == null) {
                return null;
            }
            if ("TENANT".equals(resourceType)) {
                return resourceId instanceof Number ? ((Number) resourceId).intValue() : Integer.valueOf(resourceId.toString());
            }
            if ("SERVER".equals(resourceType)) {
                McpServer server = mcpServerMapper.selectByPrimaryKey(intValue(resourceId));
                return server != null ? server.getTenantId() : null;
            }
            if ("TOOL".equals(resourceType)) {
                McpTool tool = mcpToolMapper.selectByPrimaryKey(intValue(resourceId));
                return tool != null ? resolveTenantId("SERVER", tool.getServerId()) : null;
            }
            if ("PROMPT".equals(resourceType)) {
                McpPrompt prompt = mcpPromptMapper.selectByPrimaryKey(intValue(resourceId));
                return prompt != null ? resolveTenantId("SERVER", prompt.getServerId()) : null;
            }
            if ("RESOURCE".equals(resourceType)) {
                McpResource resource = mcpResourceMapper.selectByPrimaryKey(intValue(resourceId));
                return resource != null ? resolveTenantId("SERVER", resource.getServerId()) : null;
            }
        } catch (Exception ignore) {
            // 推导失败不影响主流程
        }
        return null;
    }

    private Integer intValue(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : Integer.valueOf(value.toString());
    }

    /** 分页大小上限，避免单请求拖垮控制台 */
    private int clampPageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 10;
        }
        return Math.min(pageSize, 200);
    }

    /** Server 变更后自动刷新动态路由（保存即生效，无需手动刷新/重启） */
    private void refreshDynamicRoutes() {
        try {
            configChangePublisher.routesChanged();
        } catch (Exception e) {
            log.error("[ROUTE] 自动刷新动态路由失败", e);
        }
    }

    /** 规范化上游类型：默认 NONE；仅 MCP/AGGREGATE 保留 upstream_url */
    private void normalizeUpstream(McpServer server) {
        if (!StringUtils.hasText(server.getUpstreamType())) {
            server.setUpstreamType("NONE");
        }
        server.setUpstreamType(server.getUpstreamType().trim().toUpperCase());
        if (!"MCP".equals(server.getUpstreamType()) && !"AGGREGATE".equals(server.getUpstreamType())) {
            server.setUpstreamType("NONE");
            server.setUpstreamUrl(null);
        }
    }

    /** 工具变更：失效缓存 + 广播（整 server 粒度，数据面按需重建向量索引） */
    private void onToolChanged(Integer serverId) {
        configChangePublisher.toolChanged(serverId);
    }

    /** 工具变更：精确到单个工具，数据面增量维护向量索引 */
    private void onToolChanged(Integer serverId, Integer toolId, String op) {
        configChangePublisher.toolChanged(serverId, toolId, op);
    }

    // ============ 通用审批流 approval_request ============

    @Operation(summary = "审批策略矩阵（按当前角色）")
    @GetMapping("/approvals/policy")
    public Result<Map<String, Object>> approvalPolicy() {
        RoleEnum role = currentRole();
        if (role == null) {
            return Result.fail("未登录");
        }
        Map<String, Object> actions = new LinkedHashMap<>();
        for (ApprovalAction action : ApprovalAction.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tier", action.getTier().name());
            item.put("tierLabel", action.getTier().getLabel());
            item.put("label", action.getLabel());
            item.put("direct", ApprovalPolicy.canDirect(action, role));
            item.put("canInitiate", ApprovalPolicy.canInitiate(action, role));
            item.put("approverRole", ApprovalPolicy.requiredApproverRole(action).getCode());
            actions.put(action.getCode(), item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", role.getCode());
        result.put("actions", actions);
        return Result.success(result);
    }

    @Operation(summary = "审批申请列表")
    @GetMapping("/approvals")
    public Result<PageResult<ApprovalRequest>> listApprovals(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "all") String scope) {
        pageSize = clampPageSize(pageSize);
        int start = (Math.max(pageNum, 1) - 1) * pageSize;
        RoleEnum role = currentRole();
        if (role == null) {
            return Result.fail("未登录");
        }
        if (!StringUtils.hasText(status) || "ALL".equalsIgnoreCase(status)) {
            status = null;
        }
        String requester = null;
        String excludeRequester = null;
        List<String> actions = null;
        String normalizedScope = scope == null ? "all" : scope.trim().toLowerCase();
        if ("mine".equals(normalizedScope)) {
            requester = currentUsername();
        } else if ("pending".equals(normalizedScope)) {
            status = "PENDING";
            excludeRequester = currentUsername();
            actions = approvableActionCodes(role);
        } else if (ApprovalPolicy.level(role) < ApprovalPolicy.level(RoleEnum.ADMIN)) {
            // 开发者只能看自己提交的；管理员/超管可看本租户全部
            requester = currentUsername();
        }
        if (actions != null && actions.isEmpty()) {
            return Result.success(new PageResult<>(new ArrayList<>(), 0, pageNum, pageSize));
        }
        List<Integer> tenantIds = visibleTenantIds();
        if (tenantIds != null && tenantIds.isEmpty()) {
            return Result.success(new PageResult<>(new ArrayList<>(), 0, pageNum, pageSize));
        }
        List<ApprovalRequest> list = approvalRequestMapper.selectWithPage(
                status, requester, excludeRequester, tenantIds, actions, start, pageSize);
        long total = approvalRequestMapper.count(status, requester, excludeRequester, tenantIds, actions);
        for (ApprovalRequest item : list) {
            sanitizeApproval(item, false);
        }
        return Result.success(new PageResult<>(list, total, pageNum, pageSize));
    }

    @Operation(summary = "提交审批申请")
    @PostMapping("/approvals")
    public Result<ApprovalRequest> createApproval(@RequestBody ApprovalCreateRequest body) {
        RoleEnum role = currentRole();
        AdminAuthContext ctx = AdminAuthContext.get();
        if (role == null || ctx == null || !StringUtils.hasText(ctx.getUsername())) {
            return Result.fail("未登录");
        }
        ApprovalAction action = ApprovalAction.getByCode(body == null ? null : body.getAction());
        if (action == null) {
            return Result.fail("不支持的审批动作");
        }
        if (ApprovalPolicy.canDirect(action, role)) {
            return Result.fail("当前角色可直接执行该操作，无需提交审批");
        }
        if (!ApprovalPolicy.canInitiate(action, role)) {
            return Result.fail("当前角色无权发起该审批");
        }

        ApprovalRequest req = new ApprovalRequest();
        req.setAction(action.getCode());
        req.setStatus("PENDING");
        req.setRequester(ctx.getUsername());
        req.setRequesterRole(role.getCode());
        req.setReason(body.getReason());
        Integer tenantId;
        String payloadJson;
        try {
            tenantId = fillApprovalTarget(action, body, req);
            payloadJson = buildApprovalPayload(action, body, req);
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
        req.setTenantId(tenantId);
        if (payloadJson != null) {
            // 快照可能包含下游凭证，整体加密落库
            req.setPayload(secretCrypto.encrypt(payloadJson));
        }
        if (approvalRequestMapper.countPendingSame(action.getCode(), ctx.getUsername(), req.getAppId(),
                req.getServerId(), req.getToolId(), req.getPromptId(), req.getResourceId(), req.getUserId()) > 0) {
            return Result.fail("已有相同的待审批申请，请勿重复提交");
        }
        Date now = new Date();
        req.setCreateTime(now);
        req.setUpdateTime(now);
        approvalRequestMapper.insertSelective(req);
        recordOperation("REQUEST", "APPROVAL", req.getId(),
                action.getCode() + " " + approvalTargetSummary(req), tenantId);
        sanitizeApproval(req, true);
        return Result.success(req);
    }

    @Operation(summary = "审批详情")
    @GetMapping("/approvals/{id}")
    public Result<ApprovalRequest> getApproval(@PathVariable Long id) {
        ApprovalRequest req = approvalRequestMapper.selectByPrimaryKey(id);
        if (req == null) {
            return Result.fail("申请单不存在");
        }
        if (!canViewApproval(req)) {
            return Result.fail("无权限查看该申请");
        }
        sanitizeApproval(req, true);
        return Result.success(req);
    }

    @Operation(summary = "审批通过并执行")
    @PostMapping("/approvals/{id}/approve")
    @Transactional(rollbackFor = Exception.class)
    public Result<ApprovalRequest> approveApproval(@PathVariable Long id,
                                                   @RequestBody(required = false) ApprovalDecisionRequest body) {
        return decideApproval(id, true, body == null ? null : body.getComment());
    }

    @Operation(summary = "审批驳回")
    @PostMapping("/approvals/{id}/reject")
    @Transactional(rollbackFor = Exception.class)
    public Result<ApprovalRequest> rejectApproval(@PathVariable Long id,
                                                  @RequestBody(required = false) ApprovalDecisionRequest body) {
        return decideApproval(id, false, body == null ? null : body.getComment());
    }

    @Operation(summary = "撤回自己的申请")
    @PostMapping("/approvals/{id}/cancel")
    @Transactional(rollbackFor = Exception.class)
    public Result<ApprovalRequest> cancelApproval(@PathVariable Long id,
                                                  @RequestBody(required = false) ApprovalDecisionRequest body) {
        RoleEnum role = currentRole();
        AdminAuthContext ctx = AdminAuthContext.get();
        if (role == null || ctx == null) {
            return Result.fail("未登录");
        }
        ApprovalRequest req = approvalRequestMapper.selectByPrimaryKeyForUpdate(id);
        if (req == null) {
            return Result.fail("申请单不存在");
        }
        if (!"PENDING".equals(req.getStatus())) {
            return Result.fail("该申请已处理，无法撤回");
        }
        if (!Objects.equals(req.getRequester(), ctx.getUsername())) {
            return Result.fail("只能撤回自己提交的申请");
        }
        int n = approvalRequestMapper.updateStatusIfPending(id, "CANCELLED", null, null,
                body == null ? null : body.getComment(), null, new Date());
        if (n == 0) {
            return Result.fail("该申请已被处理");
        }
        recordOperation("CANCEL", "APPROVAL", id, req.getAction(), req.getTenantId());
        ApprovalRequest updated = approvalRequestMapper.selectByPrimaryKey(id);
        sanitizeApproval(updated, true);
        return Result.success(updated);
    }

    @Operation(summary = "查看审批通过后生成的应用密钥")
    @GetMapping("/approvals/{id}/secret")
    public Result<Map<String, Object>> revealApprovalSecret(@PathVariable Long id) {
        RoleEnum role = currentRole();
        AdminAuthContext ctx = AdminAuthContext.get();
        ApprovalRequest req = approvalRequestMapper.selectByPrimaryKey(id);
        if (req == null || ctx == null) {
            return Result.fail("申请单不存在");
        }
        if (!"APPROVED".equals(req.getStatus())) {
            return Result.fail("该申请尚未通过");
        }
        if (!Objects.equals(req.getRequester(), ctx.getUsername()) && role != RoleEnum.SUPER_ADMIN) {
            return Result.fail("只有申请人或平台超管可以查看该密钥");
        }
        if (!StringUtils.hasText(req.getResultSecret())) {
            return Result.fail("该申请没有可查看的密钥");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("secret", secretCrypto.decrypt(req.getResultSecret()));
        return Result.success(data);
    }

    // ---- 审批：提交校验 ----

    /**
     * 校验申请目标并回填审批单字段，返回归属租户。
     * 失败抛 IllegalArgumentException，由调用方转成业务错误。
     */
    private Integer fillApprovalTarget(ApprovalAction action, ApprovalCreateRequest body, ApprovalRequest req) {
        switch (action) {
            case SERVER_CREATE: {
                McpServer payload = payloadAs(body, McpServer.class);
                if (payload.getTenantId() == null) {
                    throw new IllegalArgumentException("请指定归属业务方");
                }
                if (!canManageTenantId(payload.getTenantId())) {
                    throw new IllegalArgumentException("无权限在该业务方下创建 Server");
                }
                if (!StringUtils.hasText(payload.getPath())) {
                    throw new IllegalArgumentException("路径 path 不能为空");
                }
                req.setServerId(null);
                return payload.getTenantId();
            }
            case SERVER_UPDATE_CONNECTION: {
                McpServer server = requireServer(body.getServerId());
                McpServer payload = payloadAs(body, McpServer.class);
                if (!isServerConnectionChange(server, payload)) {
                    throw new IllegalArgumentException("仅连接配置（路径/上游地址/身份断言）变更需要审批，其他字段可直接修改");
                }
                req.setServerId(server.getId());
                return server.getTenantId();
            }
            case SERVER_DELETE: {
                McpServer server = requireServer(body.getServerId());
                req.setServerId(server.getId());
                return server.getTenantId();
            }
            case TOOL_CREDENTIAL_UPDATE: {
                McpTool tool = requireTool(body.getToolId());
                McpTool payload = payloadAs(body, McpTool.class);
                if (!StringUtils.hasText(payload.getAuthConfig())) {
                    throw new IllegalArgumentException("请填写下游凭证 authConfig");
                }
                if (Objects.equals(payload.getAuthConfig(), secretCrypto.decrypt(tool.getAuthConfig()))) {
                    throw new IllegalArgumentException("下游凭证未变更，无需审批");
                }
                McpServer server = requireServer(tool.getServerId());
                req.setServerId(server.getId()).setToolId(tool.getId());
                return server.getTenantId();
            }
            case USER_ROLE_UPDATE: {
                if (body.getUserId() == null) {
                    throw new IllegalArgumentException("请选择目标用户");
                }
                AdminUser user = adminUserMapper.selectByPrimaryKey(body.getUserId());
                if (user == null) {
                    throw new IllegalArgumentException("用户不存在");
                }
                String roleCode = payloadRole(body);
                RoleEnum targetRole = RoleEnum.getByCode(roleCode);
                if (targetRole == null) {
                    throw new IllegalArgumentException("角色不合法");
                }
                if (targetRole == RoleEnum.SUPER_ADMIN) {
                    throw new IllegalArgumentException("平台超管角色不能通过审批申请授予");
                }
                if (roleCode.equals(user.getRole())) {
                    throw new IllegalArgumentException("目标角色与当前角色相同");
                }
                AdminAuthContext ctx = AdminAuthContext.get();
                if (ctx != null && ctx.getUserId() != null && ctx.getUserId().equals(user.getId())) {
                    throw new IllegalArgumentException("不能申请变更自己的角色");
                }
                List<Integer> userTenants = adminUserTenantMapper.selectTenantIdsByUserId(user.getId());
                Integer tenantId = CollectionUtils.isEmpty(userTenants) ? null : userTenants.get(0);
                if (!canManageTenantId(tenantId)) {
                    throw new IllegalArgumentException("无权限操作该用户");
                }
                req.setUserId(user.getId());
                return tenantId;
            }
            default:
                throw new IllegalArgumentException("不支持的审批动作");
        }
    }

    /** 生成执行快照 JSON（调用前 target 已回填） */
    private String buildApprovalPayload(ApprovalAction action, ApprovalCreateRequest body, ApprovalRequest req) {
        switch (action) {
            case SERVER_DELETE:
            case SERVER_CREATE:
            case SERVER_UPDATE_CONNECTION:
            case TOOL_CREDENTIAL_UPDATE:
            case USER_ROLE_UPDATE:
                return com.alibaba.fastjson.JSON.toJSONString(body.getPayload());
            default:
                return null;
        }
    }

    /** 审批通过后按快照执行；返回需要一次性展示的明文密钥（无则 null） */
    private String executeApproved(ApprovalRequest req) {
        ApprovalAction action = ApprovalAction.getByCode(req.getAction());
        if (action == null) {
            throw new IllegalArgumentException("未知审批动作");
        }
        switch (action) {
            case SERVER_CREATE:
                ensureOk(doCreateServer(readPayload(req, McpServer.class)));
                return null;
            case SERVER_UPDATE_CONNECTION:
                ensureOk(doUpdateServer(req.getServerId(), readPayload(req, McpServer.class)));
                return null;
            case SERVER_DELETE:
                ensureOk(doDeleteServer(req.getServerId()));
                return null;
            case TOOL_CREDENTIAL_UPDATE:
                ensureOk(doUpdateTool(req.getToolId(), readPayload(req, McpTool.class)));
                return null;
            case USER_ROLE_UPDATE: {
                AdminUserRequest userRequest = new AdminUserRequest();
                userRequest.setRole(readRole(req));
                adminAuthService.updateUser(req.getUserId(), userRequest);
                return null;
            }
            default:
                throw new IllegalArgumentException("不支持的审批动作");
        }
    }

    private Result<ApprovalRequest> decideApproval(Long id, boolean approve, String comment) {
        RoleEnum role = currentRole();
        AdminAuthContext ctx = AdminAuthContext.get();
        if (role == null || ctx == null || !StringUtils.hasText(ctx.getUsername())) {
            return Result.fail("未登录");
        }
        ApprovalRequest req = approvalRequestMapper.selectByPrimaryKeyForUpdate(id);
        if (req == null) {
            return Result.fail("申请单不存在");
        }
        if (!"PENDING".equals(req.getStatus())) {
            return Result.fail("该申请已处理");
        }
        ApprovalAction action = ApprovalAction.getByCode(req.getAction());
        if (action == null) {
            return Result.fail("审批动作无效");
        }
        if (!ApprovalPolicy.canApprove(action, role)) {
            return Result.fail("当前角色无权审批该操作");
        }
        if (!canManageTenantId(req.getTenantId())) {
            return Result.fail("无权限审批该申请");
        }
        if (Objects.equals(req.getRequester(), ctx.getUsername())) {
            return Result.fail("不能审批自己提交的申请");
        }
        RoleEnum requesterRole = RoleEnum.getByCode(req.getRequesterRole());
        if (ApprovalPolicy.level(role) <= ApprovalPolicy.level(requesterRole)) {
            return Result.fail("审批人角色必须高于申请人");
        }

        // 先落状态再执行：删除类审批执行时会级联清理审批单，不能依赖执行后再更新
        int updated = approvalRequestMapper.updateStatusIfPending(id,
                approve ? "APPROVED" : "REJECTED",
                ctx.getUsername(), role.getCode(), comment, null, new Date());
        if (updated == 0) {
            return Result.fail("该申请已被处理");
        }
        if (approve) {
            // 执行失败抛运行时异常，事务整体回滚（含上面的状态变更）
            String resultSecret = executeApproved(req);
            if (resultSecret != null) {
                approvalRequestMapper.updateResultSecret(id, secretCrypto.encrypt(resultSecret));
            }
        }
        recordOperation(approve ? "APPROVE" : "REJECT", "APPROVAL", id, action.getCode(), req.getTenantId());
        ApprovalRequest latest = approvalRequestMapper.selectByPrimaryKey(id);
        if (latest == null) {
            // 删除类操作已把申请单随资源一并清理，回填响应
            req.setStatus(approve ? "APPROVED" : "REJECTED");
            req.setApprover(ctx.getUsername());
            req.setApproverRole(role.getCode());
            req.setComment(comment);
            req.setUpdateTime(new Date());
            latest = req;
        }
        sanitizeApproval(latest, true);
        return Result.success(latest);
    }

    // ---- 审批：辅助方法 ----

    private RoleEnum currentRole() {
        AdminAuthContext ctx = AdminAuthContext.get();
        return ctx == null ? null : ctx.getRoleEnum();
    }

    private String currentUsername() {
        AdminAuthContext ctx = AdminAuthContext.get();
        return ctx == null ? null : ctx.getUsername();
    }

    /**
     * 直写接口审批拦截：可直写返回 null；需审批或无权返回提示文案。
     */
    private String denyDirect(ApprovalAction action) {
        RoleEnum role = currentRole();
        if (role == null) {
            return "未登录";
        }
        if (ApprovalPolicy.canDirect(action, role)) {
            return null;
        }
        if (!ApprovalPolicy.canInitiate(action, role)) {
            return "当前角色无权执行该操作";
        }
        return ApprovalPolicy.approvalHint(action);
    }

    private boolean canViewApproval(ApprovalRequest req) {
        AdminAuthContext ctx = AdminAuthContext.get();
        if (req == null || ctx == null) {
            return false;
        }
        if (!canManageTenantId(req.getTenantId())) {
            return false;
        }
        if (ApprovalPolicy.level(ctx.getRoleEnum()) >= ApprovalPolicy.level(RoleEnum.ADMIN)) {
            return true;
        }
        return Objects.equals(req.getRequester(), ctx.getUsername());
    }

    private void sanitizeApproval(ApprovalRequest req, boolean withPayload) {
        if (req == null) {
            return;
        }
        req.setResultSecret(null);
        if (!withPayload) {
            req.setPayload(null);
            return;
        }
        if (StringUtils.hasText(req.getPayload())) {
            try {
                req.setPayload(ApprovalPayloads.mask(secretCrypto.decrypt(req.getPayload())));
            } catch (Exception e) {
                req.setPayload("***");
            }
        }
    }

    private List<String> approvableActionCodes(RoleEnum role) {
        List<String> codes = new ArrayList<>();
        if (role == null) {
            return codes;
        }
        for (ApprovalAction action : ApprovalAction.values()) {
            if (action.getTier() == ApprovalTier.L4) {
                continue;
            }
            if (ApprovalPolicy.canApprove(action, role)) {
                codes.add(action.getCode());
            }
        }
        return codes;
    }

    private McpServer requireServer(Integer serverId) {
        if (serverId == null) {
            throw new IllegalArgumentException("serverId 不能为空");
        }
        McpServer server = mcpServerMapper.selectByPrimaryKey(serverId);
        if (server == null) {
            throw new IllegalArgumentException("Server 不存在");
        }
        if (!canManageServer(serverId)) {
            throw new IllegalArgumentException("无权限操作该 Server");
        }
        return server;
    }

    private McpTool requireTool(Integer toolId) {
        if (toolId == null) {
            throw new IllegalArgumentException("toolId 不能为空");
        }
        McpTool tool = mcpToolMapper.selectByPrimaryKey(toolId);
        if (tool == null || tool.getServerId() == null) {
            throw new IllegalArgumentException("工具不存在");
        }
        if (!canManageTool(toolId)) {
            throw new IllegalArgumentException("无权限操作该工具");
        }
        return tool;
    }

    private McpPrompt requirePrompt(Integer promptId) {
        if (promptId == null) {
            throw new IllegalArgumentException("promptId 不能为空");
        }
        McpPrompt prompt = mcpPromptMapper.selectByPrimaryKey(promptId);
        if (prompt == null || prompt.getServerId() == null) {
            throw new IllegalArgumentException("Prompt 不存在");
        }
        if (!canManageServer(prompt.getServerId())) {
            throw new IllegalArgumentException("无权限操作该 Prompt");
        }
        return prompt;
    }

    private McpResource requireResource(Integer resourceId) {
        if (resourceId == null) {
            throw new IllegalArgumentException("resourceId 不能为空");
        }
        McpResource resource = mcpResourceMapper.selectByPrimaryKey(resourceId);
        if (resource == null || resource.getServerId() == null) {
            throw new IllegalArgumentException("Resource 不存在");
        }
        if (!canManageServer(resource.getServerId())) {
            throw new IllegalArgumentException("无权限操作该 Resource");
        }
        return resource;
    }

    private <T> T payloadAs(ApprovalCreateRequest body, Class<T> clazz) {
        if (body == null || body.getPayload() == null) {
            throw new IllegalArgumentException("缺少执行快照 payload");
        }
        try {
            T value = com.alibaba.fastjson.JSON.parseObject(
                    com.alibaba.fastjson.JSON.toJSONString(body.getPayload()), clazz);
            if (value == null) {
                throw new IllegalArgumentException("payload 格式不正确");
            }
            return value;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("payload 解析失败");
        }
    }

    private String payloadRole(ApprovalCreateRequest body) {
        if (body == null || body.getPayload() == null) {
            throw new IllegalArgumentException("缺少目标角色");
        }
        Object role = body.getPayload().get("role");
        if (role == null) {
            throw new IllegalArgumentException("缺少目标角色");
        }
        return String.valueOf(role);
    }

    private <T> T readPayload(ApprovalRequest req, Class<T> clazz) {
        if (!StringUtils.hasText(req.getPayload())) {
            throw new IllegalArgumentException("审批单缺少执行快照");
        }
        String json = secretCrypto.decrypt(req.getPayload());
        T value = com.alibaba.fastjson.JSON.parseObject(json, clazz);
        if (value == null) {
            throw new IllegalArgumentException("审批单快照格式错误");
        }
        return value;
    }

    private Long readRelId(ApprovalRequest req) {
        if (!StringUtils.hasText(req.getPayload())) {
            throw new IllegalArgumentException("审批单缺少执行快照");
        }
        com.alibaba.fastjson.JSONObject obj = com.alibaba.fastjson.JSON.parseObject(secretCrypto.decrypt(req.getPayload()));
        Long relId = obj == null ? null : obj.getLong("relId");
        if (relId == null) {
            throw new IllegalArgumentException("审批单缺少授权关系 ID");
        }
        return relId;
    }

    private String readRole(ApprovalRequest req) {
        if (!StringUtils.hasText(req.getPayload())) {
            throw new IllegalArgumentException("审批单缺少执行快照");
        }
        com.alibaba.fastjson.JSONObject obj = com.alibaba.fastjson.JSON.parseObject(secretCrypto.decrypt(req.getPayload()));
        String role = obj == null ? null : obj.getString("role");
        if (!StringUtils.hasText(role)) {
            throw new IllegalArgumentException("审批单缺少目标角色");
        }
        return role;
    }

    private void ensureOk(Result<?> result) {
        if (result == null) {
            throw new IllegalArgumentException("执行失败");
        }
        if (result.getCode() != 0) {
            throw new IllegalArgumentException(result.getMessage());
        }
    }

    private String approvalTargetSummary(ApprovalRequest req) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(req.getAppId())) {
            sb.append("app=").append(req.getAppId()).append(' ');
        }
        if (req.getServerId() != null) {
            sb.append("server=").append(req.getServerId()).append(' ');
        }
        if (req.getToolId() != null) {
            sb.append("tool=").append(req.getToolId()).append(' ');
        }
        if (req.getPromptId() != null) {
            sb.append("prompt=").append(req.getPromptId()).append(' ');
        }
        if (req.getResourceId() != null) {
            sb.append("resource=").append(req.getResourceId()).append(' ');
        }
        if (req.getUserId() != null) {
            sb.append("user=").append(req.getUserId()).append(' ');
        }
        return sb.toString().trim();
    }

    /** Server 连接层字段是否变化（路径 / 上游地址 / 身份断言） */
    private boolean isServerConnectionChange(McpServer oldServer, McpServer request) {
        if (oldServer == null || request == null) {
            return false;
        }
        if (StringUtils.hasText(request.getPath())
                && !trimToEmpty(request.getPath()).equals(trimToEmpty(oldServer.getPath()))) {
            return true;
        }
        if (StringUtils.hasText(request.getUpstreamType())) {
            String type = request.getUpstreamType().trim().toUpperCase();
            if (!type.equals(trimToEmpty(oldServer.getUpstreamType()))) {
                return true;
            }
        }
        if (request.getUpstreamUrl() != null
                && !request.getUpstreamUrl().trim().equals(trimToEmpty(oldServer.getUpstreamUrl()))) {
            return true;
        }
        if (request.getIdentityPublicKey() != null
                && !request.getIdentityPublicKey().trim().equals(trimToEmpty(oldServer.getIdentityPublicKey()))) {
            return true;
        }
        return request.getAudience() != null
                && !request.getAudience().trim().equals(trimToEmpty(oldServer.getAudience()));
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private long toLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    /**
     * 当前用户可见的业务方(租户)ID 列表。超管返回 null（表示不过滤）。
     */
    private List<Integer> visibleTenantIds() {
        AdminAuthContext ctx = AdminAuthContext.get();
        if (ctx == null || ctx.isSuperAdmin()) {
            return null;
        }
        return ctx.getTenantIds();
    }

    /** 当前用户可见的 server ID 列表。超管返回 null（表示不过滤）。 */
    private List<Integer> visibleServerIds() {
        List<Integer> tenantIds = visibleTenantIds();
        if (tenantIds == null) {
            return null;
        }
        if (tenantIds.isEmpty()) {
            return new ArrayList<>();
        }
        List<McpServer> servers = mcpServerMapper.selectByTenantIds(tenantIds);
        return servers.stream().map(McpServer::getId).collect(Collectors.toList());
    }

    /** 校验当前用户是否能管理指定业务方 */
    private boolean canManageTenantId(Integer tenantId) {
        List<Integer> tenantIds = visibleTenantIds();
        if (tenantIds == null) {
            return true;
        }
        return tenantId != null && tenantIds.contains(tenantId);
    }

    /** 校验当前用户是否能管理指定 server（超管通过；租户级用户需该 server 归属自己业务方） */
    private boolean canManageServer(Integer serverId) {
        List<Integer> tenantIds = visibleTenantIds();
        if (tenantIds == null) {
            return true;
        }
        McpServer server = mcpServerMapper.selectByPrimaryKey(serverId);
        return server != null && server.getTenantId() != null && tenantIds.contains(server.getTenantId());
    }

    /** 校验当前用户是否能管理指定工具（超管通过；租户级用户需该工具所属 server 归属自己业务方） */
    private boolean canManageTool(Integer toolId) {
        List<Integer> tenantIds = visibleTenantIds();
        if (tenantIds == null) {
            return true;
        }
        McpTool tool = mcpToolMapper.selectByPrimaryKey(toolId);
        if (tool == null || tool.getServerId() == null) {
            return false;
        }
        return canManageServer(tool.getServerId());
    }

    /**
     * 统一异常处理：返回 HTTP 200 + code!=0，便于前端读取 message 展示。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("[MCP_ADMIN] 处理异常", e);
        // 仅业务校验异常回传具体原因，其余统一兜底，避免泄露 SQL/内部路径
        if (e instanceof IllegalArgumentException) {
            return Result.fail(e.getMessage());
        }
        return Result.fail("服务器内部错误，请联系管理员");
    }
}
