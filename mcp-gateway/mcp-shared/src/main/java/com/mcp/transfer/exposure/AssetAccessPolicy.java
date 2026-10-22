package com.mcp.transfer.exposure;

import com.mcp.transfer.cache.PromptListCache;
import com.mcp.transfer.cache.ResourceListCache;
import com.mcp.transfer.entity.McpPrompt;
import com.mcp.transfer.entity.McpResource;
import com.mcp.transfer.entity.McpServer;
import com.mcp.transfer.mapper.McpPromptMapper;
import com.mcp.transfer.mapper.McpResourceMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Prompt / Resource 可见性策略：与 Tool 使用同一套访问模式（WHITELIST / ALL）。
 *
 * <p>Prompt/Resource 不做 META/热工具，只需要解决授权一致性、数量与内容大小。
 */
@Component
public class AssetAccessPolicy {

    @Autowired
    private McpPromptMapper mcpPromptMapper;

    @Autowired
    private McpResourceMapper mcpResourceMapper;

    @Autowired
    private PromptListCache promptListCache;

    @Autowired
    private ResourceListCache resourceListCache;

    public List<McpPrompt> allPrompts(Integer serverId) {
        if (serverId == null) {
            return Collections.emptyList();
        }
        List<McpPrompt> prompts = promptListCache.get(serverId);
        if (prompts == null) {
            prompts = mcpPromptMapper.selectAllByServerId(serverId);
            if (prompts == null) {
                prompts = Collections.emptyList();
            }
            promptListCache.put(serverId, prompts);
        }
        return prompts;
    }

    public List<McpResource> allResources(Integer serverId) {
        if (serverId == null) {
            return Collections.emptyList();
        }
        List<McpResource> resources = resourceListCache.get(serverId);
        if (resources == null) {
            resources = mcpResourceMapper.selectAllByServerId(serverId);
            if (resources == null) {
                resources = Collections.emptyList();
            }
            resourceListCache.put(serverId, resources);
        }
        return resources;
    }

    public List<McpPrompt> visiblePrompts(McpServer server, String appId, boolean adminView) {
        if (server == null) {
            return Collections.emptyList();
        }
        return filterPrompts(server, appId, adminView, allPrompts(server.getId()));
    }

    public List<McpResource> visibleResources(McpServer server, String appId, boolean adminView) {
        if (server == null) {
            return Collections.emptyList();
        }
        return filterResources(server, appId, adminView, allResources(server.getId()));
    }

    /** 可见性过滤：应用授权已移除，全部启用 Prompt 对登录用户可见。 */
    public List<McpPrompt> filterPrompts(McpServer server, String appId, boolean adminView, List<McpPrompt> prompts) {
        if (server == null || prompts == null || prompts.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(prompts);
    }

    /** 可见性过滤：应用授权已移除，全部启用 Resource 对登录用户可见。 */
    public List<McpResource> filterResources(McpServer server, String appId, boolean adminView, List<McpResource> resources) {
        if (server == null || resources == null || resources.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(resources);
    }

    public boolean canUsePrompt(McpServer server, String appId, McpPrompt prompt) {
        return server != null && prompt != null;
    }

    public boolean canUseResource(McpServer server, String appId, McpResource resource) {
        return server != null && resource != null;
    }

}
