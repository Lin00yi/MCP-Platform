package com.mcp.transfer.topology;

import com.mcp.transfer.shared.entity.McpEndpoint;
import com.mcp.transfer.shared.entity.McpEndpointNamespace;
import com.mcp.transfer.shared.entity.McpNamespace;
import com.mcp.transfer.shared.entity.McpNamespaceServer;
import com.mcp.transfer.shared.entity.McpServer;
import com.mcp.transfer.shared.entity.McpTool;
import com.mcp.transfer.exposure.MetaToolFactory;
import com.mcp.transfer.exposure.ToolAccessPolicy;
import com.mcp.transfer.exposure.ToolBudget;
import com.mcp.transfer.exposure.ToolExposureModes;
import com.mcp.transfer.exposure.ToolExposureProperties;
import com.mcp.transfer.shared.mapper.McpEndpointMapper;
import com.mcp.transfer.shared.mapper.McpEndpointNamespaceMapper;
import com.mcp.transfer.shared.mapper.McpNamespaceMapper;
import com.mcp.transfer.shared.mapper.McpNamespaceServerMapper;
import com.mcp.transfer.shared.mapper.McpServerMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Namespace / Endpoint 拓扑的<b>只读</b>解析（数据面 + 发现）。
 *
 * <p>职责边界：
 * <ul>
 *   <li>本类只读 {@code mcp_endpoint*} / {@code mcp_namespace*} / {@code mcp_server}，
 *       不做任何写操作，因此可被数据面与发现接口安全复用；</li>
 *   <li>管理端的增删改在 {@code admin.topology.AccessTopologyAdminService}，
 *       两者之间没有共享可变状态。</li>
 * </ul>
 */
@Service
public class EndpointScopeReader {

    @Value("${mcp.public-base-url:http://127.0.0.1:8081}")
    private String publicBaseUrl;

    @Autowired
    private McpNamespaceMapper namespaceMapper;

    @Autowired
    private McpNamespaceServerMapper namespaceServerMapper;

    @Autowired
    private McpEndpointMapper endpointMapper;

    @Autowired
    private McpEndpointNamespaceMapper endpointNamespaceMapper;

    @Autowired
    private McpServerMapper mcpServerMapper;

    @Autowired
    private ToolAccessPolicy toolAccessPolicy;

    @Autowired
    private ToolExposureProperties toolExposureProperties;

    /**
     * Endpoint 运行时范围缓存：数据面每个 MCP 请求都会解析一次拓扑，原先一次请求要打 9~17 条 SQL，
     * 这里加 10s 的短 TTL 缓存；cached value 为不可抛出的 {@link Optional}（null 表示 Endpoint 不存在/停用）。
     */
    private final Cache<String, Optional<EndpointScope>> scopeCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(10))
            .maximumSize(2000)
            .build();

    /** 统一入口目录缓存：探测/发现客户端会高频拉取，5s 内复用（内部同样走 scopeCache）。 */
    private final Cache<String, List<Map<String, Object>>> discoveryCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(5))
            .maximumSize(1)
            .build();

    private static final String DISCOVERY_CACHE_KEY = "all";

    /** 解析 Endpoint 的运行时范围：只校验 Endpoint 与成员 Server 启用，不做应用授权。 */
    public EndpointScope resolveScope(String endpointKey) {
        if (!StringUtils.hasText(endpointKey)) {
            return null;
        }
        Optional<EndpointScope> cached = scopeCache.get(endpointKey, key -> Optional.ofNullable(loadScope(key)));
        return cached == null ? null : cached.orElse(null);
    }

    /** 失效全部拓扑缓存（配置变更事件、手动排障用）。 */
    public void invalidateAll() {
        scopeCache.invalidateAll();
        discoveryCache.invalidateAll();
    }

    /** 失效单个 Endpoint 的拓扑缓存。 */
    public void invalidate(String endpointKey) {
        if (StringUtils.hasText(endpointKey)) {
            scopeCache.invalidate(endpointKey);
        }
        discoveryCache.invalidateAll();
    }

    /** 真实的拓扑解析（无缓存），返回 null 表示 Endpoint 不存在或已停用。 */
    private EndpointScope loadScope(String endpointKey) {
        McpEndpoint ep = endpointMapper.selectByEndpointKey(endpointKey);
        if (ep == null || ep.getStatus() == null || ep.getStatus() != 1) {
            return null;
        }
        Map<Integer, String> prefixes = new LinkedHashMap<>();
        List<McpEndpointNamespace> bindings = endpointNamespaceMapper.selectByEndpointId(ep.getId());
        if (bindings != null) {
            for (McpEndpointNamespace binding : bindings) {
                McpNamespace ns = namespaceMapper.selectByPrimaryKey(binding.getNamespaceId());
                if (ns == null || ns.getStatus() == null || ns.getStatus() != 1
                        || !StringUtils.hasText(ns.getPrefix())) {
                    continue;
                }
                List<McpNamespaceServer> members = namespaceServerMapper.selectByNamespaceId(ns.getId());
                if (members == null) {
                    continue;
                }
                for (McpNamespaceServer member : members) {
                    McpServer server = mcpServerMapper.selectByPrimaryKey(member.getServerId());
                    if (server == null || server.getStatus() == null || server.getStatus() != 1) {
                        continue;
                    }
                    prefixes.putIfAbsent(server.getId(), ns.getPrefix());
                }
            }
        }
        return new EndpointScope(ep, prefixes);
    }

    /** 统一入口目录：返回全部启用且有资产的 Endpoint（不再按应用过滤），结果缓存 5s。 */
    public List<Map<String, Object>> discoveryServers() {
        List<Map<String, Object>> cached = discoveryCache.get(DISCOVERY_CACHE_KEY, key -> buildDiscoveryServers());
        return cached == null ? Collections.emptyList() : cached;
    }

    private List<Map<String, Object>> buildDiscoveryServers() {
        List<Map<String, Object>> result = new ArrayList<>();
        List<McpEndpoint> endpoints = endpointMapper.selectAll();
        if (CollectionUtils.isEmpty(endpoints)) {
            return Collections.unmodifiableList(result);
        }
        for (McpEndpoint ep : endpoints) {
            if (ep == null || ep.getStatus() == null || ep.getStatus() != 1) {
                continue;
            }
            EndpointScope scope = resolveScope(ep.getEndpointKey());
            if (scope == null || scope.getPrefixByServer().isEmpty()) {
                continue;
            }
            int toolCount = 0;
            int totalTools = 0;
            int exposedToolCount = 0;
            long estimatedVisibleTokens = 0;
            boolean meta = false;
            List<Map<String, Object>> members = new ArrayList<>();
            for (Map.Entry<Integer, String> entry : new TreeMap<>(scope.getPrefixByServer()).entrySet()) {
                McpServer server = mcpServerMapper.selectByPrimaryKey(entry.getKey());
                if (server == null) {
                    continue;
                }
                boolean serverMeta = toolExposureProperties.isMetaEnabled()
                        && ToolExposureModes.EXPOSURE_META.equals(ToolExposureModes.exposureOf(server));
                List<McpTool> visibleTools = toolAccessPolicy.visibleTools(server, null, false);
                int visible = visibleTools.size();
                ToolBudget budget = toolAccessPolicy.budget(server, null, false);
                toolCount += visible;
                totalTools += budget.getTotalTools();
                estimatedVisibleTokens += budget.getEstimatedTokens();
                exposedToolCount += serverMeta
                        ? toolAccessPolicy.hotTools(server, visibleTools).size() : visible;
                meta = meta || serverMeta;
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("id", server.getId());
                sm.put("serverKey", server.getServerKey());
                sm.put("name", server.getName());
                sm.put("path", server.getPath());
                sm.put("namespace", entry.getValue());
                sm.put("toolCount", visible);
                sm.put("totalToolCount", budget.getTotalTools());
                members.add(sm);
            }
            String path = EndpointPaths.of(ep.getEndpointKey());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ep.getId());
            m.put("serverKey", ep.getEndpointKey());
            m.put("name", ep.getName());
            m.put("description", ep.getDescription());
            m.put("path", path);
            m.put("url", absoluteUrl(path));
            m.put("status", 1);
            m.put("upstreamType", "ENDPOINT");
            // 暴露模式：任一成员 Server 为 META 时，客户端实际拿到的是热工具 + 元工具
            m.put("exposureMode", meta ? ToolExposureModes.EXPOSURE_META : ToolExposureModes.EXPOSURE_DIRECT);
            m.put("toolCount", toolCount);
            // 客户端实际会拿到的顶层工具数（META 下为热工具 + 元工具）
            m.put("exposedToolCount", meta ? exposedToolCount + MetaToolFactory.names().size() : exposedToolCount);
            m.put("estimatedVisibleTokens", estimatedVisibleTokens);
            m.put("totalToolCount", totalTools);
            m.put("serverCount", members.size());
            m.put("servers", Collections.unmodifiableList(members));
            m.put("updatedAt", ep.getUpdateTime());
            m.put("revision", revision(ep, members));
            result.add(Collections.unmodifiableMap(m));
        }
        // 缓存结果会被多个请求共享，统一返回不可变视图，避免调用方误改污染缓存
        return Collections.unmodifiableList(result);
    }

    private String absoluteUrl(String path) {
        String base = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }

    private String revision(McpEndpoint ep, List<Map<String, Object>> members) {
        StringBuilder sb = new StringBuilder();
        sb.append(ep.getEndpointKey()).append('|').append(ep.getUpdateTime()).append('|');
        for (Map<String, Object> m : members) {
            sb.append(m.get("id")).append(':').append(m.get("namespace"))
                    .append(':').append(m.get("toolCount")).append('|');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            int h = ep.getEndpointKey() == null ? 0 : ep.getEndpointKey().hashCode();
            h = h * 31 + members.size();
            return String.valueOf(h);
        }
    }
}
