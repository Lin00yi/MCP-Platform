package com.mcp.transfer.catalog;

import com.mcp.transfer.entity.McpServer;
import com.mcp.transfer.entity.McpTool;
import com.mcp.transfer.exposure.ToolAccessPolicy;
import com.mcp.transfer.exposure.ToolBudget;
import com.mcp.transfer.exposure.ToolExposureModes;
import com.mcp.transfer.mapper.McpServerMapper;
import com.mcp.transfer.mapper.McpToolMapper;
import com.mcp.transfer.utils.ServerKeyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 客户端 MCP 发现能力：
 * server 目录 / 工具目录（跨 server 检索）/ 健康探测。
 *
 * <p>两种调用视角：
 * <ul>
 *   <li><b>客户端</b>：必须携带 x-dsh-auth-token；返回全部启用资产（应用授权已移除）；</li>
 *   <li><b>管理台预览</b>：admin JWT 鉴权，按管理员可见租户过滤，并允许返回 tenantId 等管理字段。</li>
 * </ul>
 */
@Service
public class McpDiscoveryService {

    @Autowired
    private McpServerMapper mcpServerMapper;

    @Autowired
    private McpToolMapper mcpToolMapper;

    @Autowired
    private com.mcp.transfer.mapper.McpPromptMapper mcpPromptMapper;

    @Autowired
    private com.mcp.transfer.mapper.McpResourceMapper mcpResourceMapper;

    @Autowired
    private com.mcp.transfer.exposure.AssetAccessPolicy assetAccessPolicy;

    @Autowired
    private ToolAccessPolicy toolAccessPolicy;

    @Autowired
    private com.mcp.transfer.topology.EndpointScopeReader accessTopologyService;

    @Autowired
    private com.mcp.transfer.health.McpServerHealthService healthService;

    /** DSH 等客户端使用的网关外部地址；生产通过 MCP_PUBLIC_BASE_URL 配置 */
    @Value("${mcp.public-base-url:http://127.0.0.1:8081}")
    private String publicBaseUrl;

    /** Server 是否可见（不再按应用授权；只校验启用状态） */
    public boolean canAccessServer(String appId, Integer serverId) {
        if (serverId == null) {
            return false;
        }
        McpServer server = mcpServerMapper.selectByPrimaryKey(serverId);
        return server != null && server.getStatus() != null && server.getStatus() == 1;
    }

    private boolean tenantVisible(McpServer s, List<Integer> tenantIds) {
        if (tenantIds == null) {
            return true;
        }
        return tenantIds.contains(s.getTenantId());
    }

    /**
     * Server 目录。
     *
     * @param appId            应用 ID（客户端视角必填；管理台可为 null）
     * @param tenantIds        管理台可见租户（null=超管不过滤；客户端传 null）
     * @param includeSensitive 是否返回 tenantId 等管理字段（客户端必须 false）
     */
    public List<Map<String, Object>> listServers(String appId, List<Integer> tenantIds, boolean includeSensitive) {
        // 客户端发现只返回 Endpoint 目录；没有 Endpoint 授权时返回空。
        // 管理台预览（includeSensitive=true）仍按真实 Server 展示，方便运维定位。
        if (!includeSensitive) {
            return accessTopologyService.discoveryServers();
        }
        List<McpServer> servers = mcpServerMapper.getAllByStatus(1);
        List<Map<String, Object>> result = new ArrayList<>();
        for (McpServer s : servers) {
            if (!tenantVisible(s, tenantIds)) {
                continue;
            }
            List<McpTool> tools = toolAccessPolicy.visibleTools(s, null, true);
            ToolBudget budget = toolAccessPolicy.budget(s, null, true);
            Map<String, Object> m = new HashMap<>();
            m.put("id", s.getId());
            m.put("serverKey", serverKey(s));
            m.put("name", s.getName());
            m.put("path", s.getPath());
            m.put("url", absoluteUrl(s.getPath()));
            m.put("status", s.getStatus());
            m.put("upstreamType", s.getUpstreamType() == null ? "NONE" : s.getUpstreamType());
            m.put("toolCount", tools.size());
            m.put("totalToolCount", budget.getTotalTools());
            m.put("toolAccessMode", ToolExposureModes.accessOf(s));
            m.put("toolExposureMode", ToolExposureModes.exposureOf(s));
            m.put("hotToolCount", budget.getHotTools());
            m.put("promptCount", budget.getVisiblePrompts());
            m.put("totalPromptCount", budget.getTotalPrompts());
            m.put("resourceCount", budget.getVisibleResources());
            m.put("totalResourceCount", budget.getTotalResources());
            m.put("promptAccessMode", budget.getPromptAccessMode());
            m.put("resourceAccessMode", budget.getResourceAccessMode());
            m.put("promptOverWarn", budget.isPromptOverWarn());
            m.put("resourceOverWarn", budget.isResourceOverWarn());
            m.put("promptEstimatedTokens", budget.getPromptEstimatedTokens());
            m.put("resourceEstimatedTokens", budget.getResourceEstimatedTokens());
            m.put("warnThreshold", budget.getWarnThreshold());
            m.put("maxThreshold", budget.getMaxThreshold());
            m.put("overWarn", budget.isOverWarn());
            m.put("overMax", budget.isOverMax());
            m.put("estimatedTokens", budget.getEstimatedTokens());
            m.put("updatedAt", updatedAt(s, tools));
            m.put("revision", serverRevision(s, tools));
            if (includeSensitive) {
                m.put("tenantId", s.getTenantId());
                m.put("upstreamUrl", s.getUpstreamUrl());
            }
            result.add(m);
        }
        return result;
    }

    private String serverKey(McpServer server) {
        if (ServerKeyUtils.isValid(server.getServerKey())) {
            return server.getServerKey();
        }
        return ServerKeyUtils.fallback(server.getName(), server.getId());
    }

    private String absoluteUrl(String path) {
        if (!StringUtils.hasText(path)) {
            return null;
        }
        String base = StringUtils.hasText(publicBaseUrl) ? publicBaseUrl.trim() : "http://127.0.0.1:8081";
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return path.startsWith("/") ? base + path : base + "/" + path;
    }

    private Long updatedAt(McpServer server, List<McpTool> tools) {
        long max = time(server.getUpdateTime());
        if (tools != null) {
            for (McpTool tool : tools) {
                max = Math.max(max, time(tool.getUpdateTime()));
            }
        }
        return max == 0 ? null : max;
    }

    private long time(Date date) {
        return date == null ? 0L : date.getTime();
    }

    /**
     * Server 级 revision：包含 server 配置、工具集合及其更新时间。
     * 删除工具也会改变集合，因此不能只用 MAX(update_time)。
     */
    private String serverRevision(McpServer server, List<McpTool> tools) {
        StringBuilder sb = new StringBuilder();
        sb.append(serverKey(server)).append('|')
                .append(server.getId()).append('|')
                .append(server.getStatus()).append('|')
                .append(server.getPath()).append('|')
                .append(time(server.getUpdateTime())).append('|');
        if (tools != null) {
            tools.stream()
                    .sorted(Comparator.comparing(McpTool::getId, Comparator.nullsLast(Integer::compareTo)))
                    .forEach(t -> sb.append("t:").append(t.getId()).append(':')
                            .append(t.getStatus()).append(':')
                            .append(t.getCode()).append(':')
                            .append(time(t.getUpdateTime())).append('|'));
        }
        List<com.mcp.transfer.entity.McpPrompt> prompts = mcpPromptMapper.selectAllByServerId(server.getId());
        if (prompts != null) {
            prompts.stream().sorted(Comparator.comparing(com.mcp.transfer.entity.McpPrompt::getId,
                            Comparator.nullsLast(Integer::compareTo)))
                    .forEach(p -> sb.append("p:").append(p.getId()).append(':')
                            .append(p.getStatus()).append(':').append(time(p.getUpdateTime())).append('|'));
        }
        List<com.mcp.transfer.entity.McpResource> resources = mcpResourceMapper.selectAllByServerId(server.getId());
        if (resources != null) {
            resources.stream().sorted(Comparator.comparing(com.mcp.transfer.entity.McpResource::getId,
                            Comparator.nullsLast(Integer::compareTo)))
                    .forEach(r -> sb.append("r:").append(r.getId()).append(':')
                            .append(r.getStatus()).append(':').append(time(r.getUpdateTime())).append('|'));
        }
        return sha256(sb.toString());
    }

    /** 全局 revision：对每个 server 的 revision 排序后哈希，供 ETag/If-None-Match 使用。 */
    public String revisionOf(List<Map<String, Object>> servers) {
        StringBuilder sb = new StringBuilder();
        servers.stream()
                .sorted(Comparator.comparing(m -> String.valueOf(m.get("serverKey"))))
                .forEach(m -> sb.append(m.get("serverKey")).append('=')
                        .append(m.get("revision")).append(';'));
        return sha256(sb.toString());
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算 revision 失败", e);
        }
    }

    public List<Map<String, Object>> listTools(String appId, String q, String tag, Integer serverId, List<Integer> tenantIds) {
        return listTools(appId, q, tag, serverId, tenantIds, false);
    }

    /**
     * 工具目录（跨 server 检索）。
     *
     * @param adminView 管理台预览：未指定 appId 时按全量展示（评估最坏情况）；客户端必须 false
     */
    public List<Map<String, Object>> listTools(String appId, String q, String tag, Integer serverId,
                                               List<Integer> tenantIds, boolean adminView) {
        List<McpServer> servers = mcpServerMapper.getAllByStatus(1);
        List<Map<String, Object>> result = new ArrayList<>();
        for (McpServer s : servers) {
            if (!tenantVisible(s, tenantIds)) {
                continue;
            }
            if (serverId != null && !serverId.equals(s.getId())) {
                continue;
            }
            List<McpTool> tools = toolAccessPolicy.visibleTools(s, null, adminView);
            for (McpTool t : tools) {
                if (StringUtils.hasText(q) && !matchesQuery(t, q)) {
                    continue;
                }
                if (StringUtils.hasText(tag) && !containsTag(t.getTags(), tag)) {
                    continue;
                }
                result.add(toToolVO(s, t));
            }
        }
        return result;
    }

    /** Prompt 目录（仅启用 + 该 app 已授权 server 下的） */
    public List<Map<String, Object>> listPrompts(String appId, Integer serverId, String q, String tag, List<Integer> tenantIds) {
        List<McpServer> servers = mcpServerMapper.getAllByStatus(1);
        List<Map<String, Object>> result = new ArrayList<>();
        for (McpServer s : servers) {
            if (!tenantVisible(s, tenantIds)) {
                continue;
            }
            if (serverId != null && !serverId.equals(s.getId())) {
                continue;
            }
            for (com.mcp.transfer.entity.McpPrompt p : assetAccessPolicy.visiblePrompts(s, null, true)) {
                if (StringUtils.hasText(q) && !matchesText(q, p.getName(), p.getResJson())) {
                    continue;
                }
                if (StringUtils.hasText(tag) && (p.getTags() == null
                        || !p.getTags().toLowerCase().contains(tag.toLowerCase()))) {
                    continue;
                }
                result.add(toPromptVO(s, p));
            }
        }
        return result;
    }

    /** Resource 目录（仅启用 + 该 app 已授权 server 下的） */
    public List<Map<String, Object>> listResources(String appId, Integer serverId, String q, String tag, List<Integer> tenantIds) {
        List<McpServer> servers = mcpServerMapper.getAllByStatus(1);
        List<Map<String, Object>> result = new ArrayList<>();
        for (McpServer s : servers) {
            if (!tenantVisible(s, tenantIds)) {
                continue;
            }
            if (serverId != null && !serverId.equals(s.getId())) {
                continue;
            }
            for (com.mcp.transfer.entity.McpResource r : assetAccessPolicy.visibleResources(s, null, true)) {
                if (StringUtils.hasText(q) && !matchesText(q, r.getName(), r.getUri(), r.getResJson())) {
                    continue;
                }
                if (StringUtils.hasText(tag) && (r.getTags() == null
                        || !r.getTags().toLowerCase().contains(tag.toLowerCase()))) {
                    continue;
                }
                result.add(toResourceVO(s, r));
            }
        }
        return result;
    }

    private boolean matchesText(String q, String... parts) {
        String lower = q.toLowerCase();
        for (String p : parts) {
            if (p != null && p.toLowerCase().contains(lower)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> toPromptVO(McpServer s, com.mcp.transfer.entity.McpPrompt p) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("description", extractDescription(p.getResJson()));
        m.put("version", p.getVersion());
        m.put("tags", p.getTags());
        m.put("tokenEstimate", p.getTokenEstimate());
        m.put("server", serverMeta(s));
        return m;
    }

    private Map<String, Object> toResourceVO(McpServer s, com.mcp.transfer.entity.McpResource r) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", r.getId());
        m.put("name", r.getName());
        m.put("uri", r.getUri());
        m.put("description", extractDescription(r.getResJson()));
        m.put("version", r.getVersion());
        m.put("tags", r.getTags());
        m.put("tokenEstimate", r.getTokenEstimate());
        m.put("server", serverMeta(s));
        return m;
    }

    /** 从 res_json 中提取 description 字段（失败返回 null） */
    private String extractDescription(String resJson) {
        if (!StringUtils.hasText(resJson)) {
            return null;
        }
        try {
            com.alibaba.fastjson.JSONObject o = com.alibaba.fastjson.JSON.parseObject(resJson);
            return o == null ? null : o.getString("description");
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> serverMeta(McpServer s) {
        Map<String, Object> server = new HashMap<>();
        server.put("id", s.getId());
        server.put("name", s.getName());
        server.put("path", s.getPath());
        return server;
    }

    /**
     * 健康探测。
     *
     * @param tenantIds 管理台可见租户（null=超管）；客户端视角传 null，但调用方需先校验 canAccessServer
     */
    public Map<String, Object> health(Integer serverId, List<Integer> tenantIds) {
        Map<String, Object> m = new HashMap<>();
        McpServer s = mcpServerMapper.selectByPrimaryKey(serverId);
        if (s == null || !tenantVisible(s, tenantIds)) {
            m.put("ok", false);
            m.put("message", "server 不存在");
            return m;
        }
        int toolCount = mcpToolMapper.selectAllByServerId(s.getId()).size();
        Map<String, Object> health = healthService.healthOf(s);
        m.put("ok", health.get("ok"));
        m.put("status", health.get("status"));
        m.put("latencyMs", health.get("latencyMs"));
        m.put("successRate", health.get("successRate"));
        m.put("message", health.get("message"));
        m.put("checkedAt", health.get("checkedAt"));
        m.put("serverId", s.getId());
        m.put("name", s.getName());
        m.put("path", s.getPath());
        m.put("upstreamType", s.getUpstreamType() == null ? "NONE" : s.getUpstreamType());
        m.put("toolCount", toolCount);
        return m;
    }

    private boolean matchesQuery(McpTool t, String q) {
        String lower = q.toLowerCase();
        return (t.getCode() != null && t.getCode().toLowerCase().contains(lower))
                || (t.getName() != null && t.getName().toLowerCase().contains(lower))
                || (t.getTags() != null && t.getTags().toLowerCase().contains(lower));
    }

    private boolean containsTag(String tags, String tag) {
        if (!StringUtils.hasText(tags)) {
            return false;
        }
        for (String t : tags.split(",")) {
            if (tag.equals(t.trim())) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> toToolVO(McpServer s, McpTool t) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", t.getId());
        m.put("code", t.getCode());
        m.put("originalCode", t.getCode());
        m.put("name", t.getName());
        m.put("description", extractDescription(t.getResJson()));
        m.put("type", t.getType());
        m.put("tags", t.getTags());
        m.put("version", t.getVersion());
        Map<String, Object> server = new HashMap<>();
        server.put("id", s.getId());
        server.put("name", s.getName());
        server.put("serverKey", ServerKeyUtils.fallback(s.getName(), s.getId()));
        server.put("path", s.getPath());
        m.put("server", server);
        return m;
    }
}
