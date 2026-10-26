package com.mcp.transfer.admin.topology;

import com.mcp.transfer.admin.security.AdminAuthContext;
import com.mcp.transfer.shared.entity.McpEndpoint;
import com.mcp.transfer.shared.entity.McpEndpointNamespace;
import com.mcp.transfer.shared.entity.McpNamespace;
import com.mcp.transfer.shared.entity.McpNamespaceServer;
import com.mcp.transfer.shared.entity.McpServer;
import com.mcp.transfer.shared.entity.McpTool;
import com.mcp.transfer.topology.EndpointPaths;
import com.mcp.transfer.shared.mapper.McpEndpointMapper;
import com.mcp.transfer.shared.mapper.McpEndpointNamespaceMapper;
import com.mcp.transfer.shared.mapper.McpNamespaceMapper;
import com.mcp.transfer.shared.mapper.McpNamespaceServerMapper;
import com.mcp.transfer.shared.mapper.McpServerMapper;
import com.mcp.transfer.shared.mapper.McpToolMapper;
import com.mcp.transfer.shared.mapper.TenantMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Namespace / Endpoint 拓扑的<b>写</b>侧（控制面）。
 *
 * <p>多租户模型（2026-10-25 起）：
 * <ul>
 *   <li>{@code tenant_id = NULL}：<b>平台级</b>资源，只有平台超管可管理；可绑定多个业务方的 Server，
 *       用于「多个业务方聚合到同一个端点」的场景。</li>
 *   <li>{@code tenant_id = N}：<b>业务方级</b>资源，该业务方管理员只能管理，且只能绑定/引用本业务方的 Server。</li>
 * </ul>
 *
 * <p>与 {@code access.EndpointScopeReader} 的边界：
 * 本类只负责 CRUD 与参数校验，运行时解析不在这里。
 * 数据面不依赖本类，因此拆分后控制面可独立发布。
 */
@Service
public class AccessTopologyAdminService {

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
    private McpToolMapper mcpToolMapper;

    @Autowired
    private TenantMapper tenantMapper;

    // ==================== Namespace ====================

    public List<Map<String, Object>> listNamespaces() {
        List<McpNamespace> namespaces = namespaceMapper.selectAll();
        List<Map<String, Object>> result = new ArrayList<>();
        if (namespaces == null) {
            return result;
        }
        for (McpNamespace ns : namespaces) {
            if (!canRead(ns.getTenantId())) {
                continue;
            }
            result.add(namespaceView(ns));
        }
        return result;
    }

    private Map<String, Object> namespaceView(McpNamespace ns) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ns.getId());
        m.put("tenantId", ns.getTenantId());
        m.put("scope", ns.getTenantId() == null ? "PLATFORM" : "TENANT");
        m.put("name", ns.getName());
        m.put("prefix", ns.getPrefix());
        m.put("description", ns.getDescription());
        m.put("status", ns.getStatus());
        m.put("createTime", ns.getCreateTime());
        m.put("updateTime", ns.getUpdateTime());
        int toolCount = 0;
        List<Map<String, Object>> servers = new ArrayList<>();
        List<McpNamespaceServer> members = namespaceServerMapper.selectByNamespaceId(ns.getId());
        if (members != null) {
            for (McpNamespaceServer member : members) {
                McpServer server = mcpServerMapper.selectByPrimaryKey(member.getServerId());
                if (server == null) {
                    continue;
                }
                int tools = enabledToolCount(server.getId());
                toolCount += tools;
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("id", server.getId());
                sm.put("tenantId", server.getTenantId());
                sm.put("name", server.getName());
                sm.put("serverKey", server.getServerKey());
                sm.put("path", server.getPath());
                sm.put("status", server.getStatus());
                sm.put("toolCount", tools);
                sm.put("totalToolCount", tools);
                sm.put("upstreamType", server.getUpstreamType());
                servers.add(sm);
            }
        }
        m.put("serverCount", servers.size());
        m.put("toolCount", toolCount);
        m.put("servers", servers);
        return m;
    }

    @Transactional(rollbackFor = Exception.class)
    public McpNamespace saveNamespace(Integer id, String name, String prefix, String description, Integer status,
                                      Integer requestedTenantId) {
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("Namespace 名称不能为空");
        }
        Date now = new Date();
        if (id == null) {
            Integer tenantId = resolveCreateTenantId(requestedTenantId);
            String p = prefix == null ? "" : prefix.trim();
            if (!p.matches("^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$")) {
                throw new IllegalArgumentException("前缀仅允许字母、数字、下划线、中划线，长度 1-32，且不能以符号开头");
            }
            if (namespaceMapper.selectByPrefix(p) != null) {
                throw new IllegalArgumentException("前缀已被占用：" + p);
            }
            McpNamespace ns = new McpNamespace()
                    .setTenantId(tenantId)
                    .setName(name.trim())
                    .setPrefix(p)
                    .setDescription(description)
                    .setStatus(status == null ? 1 : status)
                    .setCreateTime(now)
                    .setUpdateTime(now);
            namespaceMapper.insertSelective(ns);
            return ns;
        }
        McpNamespace existing = namespaceMapper.selectByPrimaryKey(id);
        if (existing == null) {
            throw new IllegalArgumentException("Namespace 不存在");
        }
        requireManage(existing.getTenantId(), "Namespace");
        if (StringUtils.hasText(prefix) && !prefix.trim().equals(existing.getPrefix())) {
            throw new IllegalArgumentException("前缀创建后不可修改（客户端工具名依赖它）");
        }
        McpNamespace update = new McpNamespace()
                .setId(id)
                .setName(name.trim())
                .setDescription(description)
                .setStatus(status)
                .setUpdateTime(now);
        namespaceMapper.updateByPrimaryKeySelective(update);
        return namespaceMapper.selectByPrimaryKey(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteNamespace(Integer id) {
        McpNamespace ns = namespaceMapper.selectByPrimaryKey(id);
        if (ns == null) {
            throw new IllegalArgumentException("Namespace 不存在");
        }
        requireManage(ns.getTenantId(), "Namespace");
        endpointNamespaceMapper.deleteByNamespaceId(id);
        namespaceServerMapper.deleteByNamespaceId(id);
        namespaceMapper.deleteByPrimaryKey(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void setNamespaceServers(Integer id, List<Integer> serverIds) {
        McpNamespace ns = namespaceMapper.selectByPrimaryKey(id);
        if (ns == null) {
            throw new IllegalArgumentException("Namespace 不存在");
        }
        requireManage(ns.getTenantId(), "Namespace");
        Set<Integer> unique = new LinkedHashSet<>();
        if (serverIds != null) {
            for (Integer sid : serverIds) {
                if (sid != null) {
                    unique.add(sid);
                }
            }
        }
        // 租户级 Namespace 只能绑定本租户的 Server；平台级（tenantId=null）允许跨租户聚合
        Map<String, Integer> codeOwner = new HashMap<>();
        for (Integer sid : unique) {
            McpServer server = mcpServerMapper.selectByPrimaryKey(sid);
            if (server == null) {
                throw new IllegalArgumentException("Server 不存在：" + sid);
            }
            if (ns.getTenantId() != null && !ns.getTenantId().equals(server.getTenantId())) {
                throw new IllegalArgumentException("不能绑定其他业务方的 Server：" + sid);
            }
            // 同一 Namespace 下工具 code 不能重复，否则聚合后无法路由
            for (McpTool tool : safeTools(sid)) {
                String code = tool.getCode();
                if (!StringUtils.hasText(code)) {
                    continue;
                }
                Integer owner = codeOwner.get(code);
                if (owner != null && !owner.equals(sid)) {
                    throw new IllegalArgumentException("Tool code 冲突：" + code
                            + "（Server " + owner + " 与 " + sid + "）");
                }
                codeOwner.put(code, sid);
            }
        }
        namespaceServerMapper.deleteByNamespaceId(id);
        Date now = new Date();
        for (Integer sid : unique) {
            namespaceServerMapper.insert(new McpNamespaceServer()
                    .setNamespaceId(id).setServerId(sid).setCreateTime(now));
        }
        namespaceMapper.updateByPrimaryKeySelective(new McpNamespace()
                .setId(id).setUpdateTime(now));
    }

    // ==================== Endpoint ====================

    public List<Map<String, Object>> listEndpoints() {
        List<McpEndpoint> endpoints = endpointMapper.selectAll();
        List<Map<String, Object>> result = new ArrayList<>();
        if (endpoints == null) {
            return result;
        }
        for (McpEndpoint ep : endpoints) {
            if (!canRead(ep.getTenantId())) {
                continue;
            }
            result.add(endpointView(ep));
        }
        return result;
    }

    private Map<String, Object> endpointView(McpEndpoint ep) {
        Map<String, Object> m = new LinkedHashMap<>();
        String path = EndpointPaths.of(ep.getEndpointKey());
        m.put("id", ep.getId());
        m.put("tenantId", ep.getTenantId());
        m.put("scope", ep.getTenantId() == null ? "PLATFORM" : "TENANT");
        m.put("endpointKey", ep.getEndpointKey());
        m.put("name", ep.getName());
        m.put("description", ep.getDescription());
        m.put("status", ep.getStatus());
        m.put("path", path);
        m.put("url", absoluteUrl(path));
        m.put("createTime", ep.getCreateTime());
        m.put("updateTime", ep.getUpdateTime());

        List<Map<String, Object>> namespaces = new ArrayList<>();
        List<McpEndpointNamespace> bindings = endpointNamespaceMapper.selectByEndpointId(ep.getId());
        Set<Integer> serverIds = new LinkedHashSet<>();
        if (bindings != null) {
            for (McpEndpointNamespace binding : bindings) {
                McpNamespace ns = namespaceMapper.selectByPrimaryKey(binding.getNamespaceId());
                if (ns == null) {
                    continue;
                }
                Map<String, Object> nm = new LinkedHashMap<>();
                nm.put("id", ns.getId());
                nm.put("tenantId", ns.getTenantId());
                nm.put("name", ns.getName());
                nm.put("prefix", ns.getPrefix());
                nm.put("status", ns.getStatus());
                namespaces.add(nm);
                List<McpNamespaceServer> members = namespaceServerMapper.selectByNamespaceId(ns.getId());
                if (members != null) {
                    for (McpNamespaceServer member : members) {
                        serverIds.add(member.getServerId());
                    }
                }
            }
        }
        int toolCount = 0;
        for (Integer sid : serverIds) {
            toolCount += enabledToolCount(sid);
        }
        m.put("namespaceCount", namespaces.size());
        m.put("serverCount", serverIds.size());
        m.put("toolCount", toolCount);
        m.put("namespaces", namespaces);

        return m;
    }

    @Transactional(rollbackFor = Exception.class)
    public McpEndpoint saveEndpoint(Integer id, String endpointKey, String name, String description, Integer status,
                                    Integer requestedTenantId) {
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("Endpoint 名称不能为空");
        }
        Date now = new Date();
        if (id == null) {
            Integer tenantId = resolveCreateTenantId(requestedTenantId);
            String key = endpointKey == null ? "" : endpointKey.trim().toLowerCase();
            if (!key.matches("^[a-z0-9][a-z0-9-]{0,63}$")) {
                throw new IllegalArgumentException("Endpoint Key 仅允许小写字母、数字、中划线，长度 1-64");
            }
            if (endpointMapper.selectByEndpointKey(key) != null) {
                throw new IllegalArgumentException("Endpoint Key 已存在：" + key);
            }
            McpEndpoint ep = new McpEndpoint()
                    .setTenantId(tenantId)
                    .setEndpointKey(key)
                    .setName(name.trim())
                    .setDescription(description)
                    .setStatus(status == null ? 1 : status)
                    .setCreateTime(now)
                    .setUpdateTime(now);
            endpointMapper.insertSelective(ep);
            return ep;
        }
        McpEndpoint existing = endpointMapper.selectByPrimaryKey(id);
        if (existing == null) {
            throw new IllegalArgumentException("Endpoint 不存在");
        }
        requireManage(existing.getTenantId(), "Endpoint");
        if (StringUtils.hasText(endpointKey) && !endpointKey.trim().equalsIgnoreCase(existing.getEndpointKey())) {
            throw new IllegalArgumentException("Endpoint Key 创建后不可修改（客户端地址依赖它）");
        }
        endpointMapper.updateByPrimaryKeySelective(new McpEndpoint()
                .setId(id).setName(name.trim()).setDescription(description)
                .setStatus(status).setUpdateTime(now));
        return endpointMapper.selectByPrimaryKey(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteEndpoint(Integer id) {
        McpEndpoint ep = endpointMapper.selectByPrimaryKey(id);
        if (ep == null) {
            throw new IllegalArgumentException("Endpoint 不存在");
        }
        requireManage(ep.getTenantId(), "Endpoint");
        endpointNamespaceMapper.deleteByEndpointId(id);
        endpointMapper.deleteByPrimaryKey(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void setEndpointNamespaces(Integer id, List<Integer> namespaceIds) {
        McpEndpoint ep = endpointMapper.selectByPrimaryKey(id);
        if (ep == null) {
            throw new IllegalArgumentException("Endpoint 不存在");
        }
        requireManage(ep.getTenantId(), "Endpoint");
        Set<Integer> unique = new LinkedHashSet<>();
        if (namespaceIds != null) {
            for (Integer nid : namespaceIds) {
                if (nid == null) {
                    continue;
                }
                McpNamespace ns = namespaceMapper.selectByPrimaryKey(nid);
                if (ns == null) {
                    throw new IllegalArgumentException("Namespace 不存在：" + nid);
                }
                // 租户级 Endpoint 只能绑定本租户的 Namespace；平台级可跨租户聚合
                if (ep.getTenantId() != null && !ep.getTenantId().equals(ns.getTenantId())) {
                    throw new IllegalArgumentException("不能绑定其他业务方的 Namespace：" + nid);
                }
                unique.add(nid);
            }
        }
        endpointNamespaceMapper.deleteByEndpointId(id);
        Date now = new Date();
        for (Integer nid : unique) {
            endpointNamespaceMapper.insert(new McpEndpointNamespace()
                    .setEndpointId(id).setNamespaceId(nid).setCreateTime(now));
        }
        endpointMapper.updateByPrimaryKeySelective(new McpEndpoint().setId(id).setUpdateTime(now));
    }

    // ==================== 多租户权限 ====================

    private boolean isSuperAdmin() {
        AdminAuthContext ctx = AdminAuthContext.get();
        return ctx == null || ctx.isSuperAdmin();
    }

    /** 当前账号可管理的业务方；超管返回 null（不过滤）。 */
    private List<Integer> callerTenantIds() {
        AdminAuthContext ctx = AdminAuthContext.get();
        if (ctx == null || ctx.isSuperAdmin()) {
            return null;
        }
        return ctx.getTenantIds() == null ? Collections.emptyList() : ctx.getTenantIds();
    }

    /** 平台级资源（tenantId=null）仅超管可见；业务方级资源仅对应业务方可见。 */
    private boolean canRead(Integer tenantId) {
        if (isSuperAdmin()) {
            return true;
        }
        if (tenantId == null) {
            return false;
        }
        List<Integer> mine = callerTenantIds();
        return mine != null && mine.contains(tenantId);
    }

    /** 平台级资源仅超管可改；业务方级资源仅对应业务方管理员可改。 */
    private void requireManage(Integer tenantId, String type) {
        if (isSuperAdmin()) {
            return;
        }
        if (tenantId == null) {
            throw new IllegalArgumentException("平台级 " + type + " 仅平台超管可管理");
        }
        List<Integer> mine = callerTenantIds();
        if (mine == null || !mine.contains(tenantId)) {
            throw new IllegalArgumentException("无权管理其他业务方的 " + type);
        }
    }

    /**
     * 创建时的归属：超管默认平台级（可传 tenantId 创建业务方级）；业务方管理员强制为本业务方。
     */
    private Integer resolveCreateTenantId(Integer requestedTenantId) {
        if (isSuperAdmin()) {
            if (requestedTenantId == null) {
                return null;
            }
            if (tenantMapper.selectByPrimaryKey(requestedTenantId) == null) {
                throw new IllegalArgumentException("业务方不存在：" + requestedTenantId);
            }
            return requestedTenantId;
        }
        List<Integer> mine = callerTenantIds();
        if (mine == null || mine.isEmpty()) {
            throw new IllegalArgumentException("当前账号未分配业务方，无法创建 Namespace/Endpoint");
        }
        if (requestedTenantId != null) {
            if (!mine.contains(requestedTenantId)) {
                throw new IllegalArgumentException("无权在其他业务方下创建");
            }
            return requestedTenantId;
        }
        if (mine.size() == 1) {
            return mine.get(0);
        }
        throw new IllegalArgumentException("当前账号可管理多个业务方，请指定 tenantId");
    }

    // ==================== 内部 ====================

    private List<McpTool> safeTools(Integer serverId) {
        List<McpTool> tools = mcpToolMapper.selectAllByServerId(serverId);
        return tools == null ? Collections.emptyList() : tools;
    }

    private int enabledToolCount(Integer serverId) {
        return safeTools(serverId).size();
    }

    private String absoluteUrl(String path) {
        String base = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }
}
