package com.mcp.transfer.admin.topology;

import com.mcp.transfer.entity.McpEndpoint;
import com.mcp.transfer.entity.McpEndpointNamespace;
import com.mcp.transfer.entity.McpNamespace;
import com.mcp.transfer.entity.McpNamespaceServer;
import com.mcp.transfer.entity.McpServer;
import com.mcp.transfer.entity.McpTool;
import com.mcp.transfer.topology.EndpointPaths;
import com.mcp.transfer.mapper.McpEndpointMapper;
import com.mcp.transfer.mapper.McpEndpointNamespaceMapper;
import com.mcp.transfer.mapper.McpNamespaceMapper;
import com.mcp.transfer.mapper.McpNamespaceServerMapper;
import com.mcp.transfer.mapper.McpServerMapper;
import com.mcp.transfer.mapper.McpToolMapper;
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

    // ==================== Namespace ====================

    public List<Map<String, Object>> listNamespaces() {
        List<McpNamespace> namespaces = namespaceMapper.selectAll();
        List<Map<String, Object>> result = new ArrayList<>();
        if (namespaces == null) {
            return result;
        }
        for (McpNamespace ns : namespaces) {
            result.add(namespaceView(ns));
        }
        return result;
    }

    private Map<String, Object> namespaceView(McpNamespace ns) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ns.getId());
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
    public McpNamespace saveNamespace(Integer id, String name, String prefix, String description, Integer status) {
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("Namespace 名称不能为空");
        }
        Date now = new Date();
        if (id == null) {
            String p = prefix == null ? "" : prefix.trim();
            if (!p.matches("^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$")) {
                throw new IllegalArgumentException("前缀仅允许字母、数字、下划线、中划线，长度 1-32，且不能以符号开头");
            }
            if (namespaceMapper.selectByPrefix(p) != null) {
                throw new IllegalArgumentException("前缀已被占用：" + p);
            }
            McpNamespace ns = new McpNamespace()
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
        Set<Integer> unique = new LinkedHashSet<>();
        if (serverIds != null) {
            for (Integer sid : serverIds) {
                if (sid != null) {
                    unique.add(sid);
                }
            }
        }
        // 同一 Namespace 下工具 code 不能重复，否则聚合后无法路由
        Map<String, Integer> codeOwner = new HashMap<>();
        for (Integer sid : unique) {
            McpServer server = mcpServerMapper.selectByPrimaryKey(sid);
            if (server == null) {
                throw new IllegalArgumentException("Server 不存在：" + sid);
            }
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
            result.add(endpointView(ep));
        }
        return result;
    }

    private Map<String, Object> endpointView(McpEndpoint ep) {
        Map<String, Object> m = new LinkedHashMap<>();
        String path = EndpointPaths.of(ep.getEndpointKey());
        m.put("id", ep.getId());
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
    public McpEndpoint saveEndpoint(Integer id, String endpointKey, String name, String description, Integer status) {
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("Endpoint 名称不能为空");
        }
        Date now = new Date();
        if (id == null) {
            String key = endpointKey == null ? "" : endpointKey.trim().toLowerCase();
            if (!key.matches("^[a-z0-9][a-z0-9-]{0,63}$")) {
                throw new IllegalArgumentException("Endpoint Key 仅允许小写字母、数字、中划线，长度 1-64");
            }
            if (endpointMapper.selectByEndpointKey(key) != null) {
                throw new IllegalArgumentException("Endpoint Key 已存在：" + key);
            }
            McpEndpoint ep = new McpEndpoint()
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
        endpointNamespaceMapper.deleteByEndpointId(id);
        endpointMapper.deleteByPrimaryKey(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void setEndpointNamespaces(Integer id, List<Integer> namespaceIds) {
        McpEndpoint ep = endpointMapper.selectByPrimaryKey(id);
        if (ep == null) {
            throw new IllegalArgumentException("Endpoint 不存在");
        }
        Set<Integer> unique = new LinkedHashSet<>();
        if (namespaceIds != null) {
            for (Integer nid : namespaceIds) {
                if (nid == null) {
                    continue;
                }
                if (namespaceMapper.selectByPrimaryKey(nid) == null) {
                    throw new IllegalArgumentException("Namespace 不存在：" + nid);
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
