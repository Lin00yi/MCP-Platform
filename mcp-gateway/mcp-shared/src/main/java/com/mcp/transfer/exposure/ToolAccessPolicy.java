package com.mcp.transfer.exposure;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.mcp.transfer.cache.ToolListCache;
import com.mcp.transfer.entity.McpServer;
import com.mcp.transfer.entity.McpTool;
import com.mcp.transfer.mapper.McpToolMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 工具可见性策略：应用授权已移除，全部启用工具对登录用户可见。
 *
 * <p>数据面（tools/list、tools/call）与发现接口（/mcp/discovery/**）共用同一套规则，
 * 避免出现“目录里能看到、调用却被拒”或反之的不一致。
 */
@Component
public class ToolAccessPolicy {

    @Autowired
    private McpToolMapper mcpToolMapper;

    @Autowired
    private AssetAccessPolicy assetAccessPolicy;

    @Autowired
    private ToolListCache toolListCache;

    @Autowired
    private ToolExposureProperties properties;

    @Autowired
    private TokenEstimator tokenEstimator;

    /** server 下全部启用工具（走内存缓存，工具变更时由 ToolChangeNotifier 失效） */
    public List<McpTool> allTools(Integer serverId) {
        if (serverId == null) {
            return Collections.emptyList();
        }
        List<McpTool> tools = toolListCache.get(serverId);
        if (tools == null) {
            tools = mcpToolMapper.selectAllByServerId(serverId);
            if (tools == null) {
                tools = Collections.emptyList();
            }
            toolListCache.put(serverId, tools);
        }
        return tools;
    }

    /**
     * 某个应用在指定 server 下可见的工具。
     *
     * @param adminView 管理台预览：WHITELIST 且未指定 appId 时返回全量（用于评估最坏情况），
     *                  数据面必须传 false
     */
    public List<McpTool> visibleTools(McpServer server, String appId, boolean adminView) {
        if (server == null) {
            return Collections.emptyList();
        }
        return filter(server, appId, adminView, allTools(server.getId()));
    }

    /** 可见性过滤：应用授权已移除，全部启用工具对登录用户可见。 */
    public List<McpTool> filter(McpServer server, String appId, boolean adminView, List<McpTool> tools) {
        if (server == null || tools == null || tools.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(tools);
    }

    /** 调用准入：应用授权已移除；工具是否启用由 tools/list 的查询条件保证。 */
    public boolean canUse(McpServer server, String appId, McpTool tool) {
        return server != null && tool != null;
    }

    /** 解析 server 配置的热工具 code（JSON 数组），保持配置顺序并去重 */
    public List<String> hotToolCodes(McpServer server) {
        if (server == null || !StringUtils.hasText(server.getHotToolCodes())) {
            return Collections.emptyList();
        }
        try {
            JSONArray array = JSON.parseArray(server.getHotToolCodes());
            if (array == null || array.isEmpty()) {
                return Collections.emptyList();
            }
            LinkedHashSet<String> codes = new LinkedHashSet<>();
            for (Object item : array) {
                if (item != null && StringUtils.hasText(String.valueOf(item))) {
                    codes.add(String.valueOf(item).trim());
                }
            }
            return new ArrayList<>(codes);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /** META 模式常驻 tools/list 的热工具（只保留当前应用可见且启用的） */
    public List<McpTool> hotTools(McpServer server, List<McpTool> visibleTools) {
        List<String> codes = hotToolCodes(server);
        if (codes.isEmpty() || CollectionUtils.isEmpty(visibleTools)) {
            return Collections.emptyList();
        }
        int limit = Math.max(0, properties.getHotToolLimit());
        List<McpTool> result = new ArrayList<>();
        for (String code : codes) {
            if (result.size() >= limit) {
                break;
            }
            if (MetaToolFactory.isMetaTool(code)) {
                continue;
            }
            for (McpTool tool : visibleTools) {
                if (code.equals(tool.getCode())) {
                    result.add(tool);
                    break;
                }
            }
        }
        return result;
    }

    public ToolBudget budget(McpServer server, String appId, boolean adminView) {
        ToolBudget budget = new ToolBudget();
        if (server == null) {
            return budget;
        }
        List<McpTool> all = allTools(server.getId());
        List<McpTool> visible = filter(server, appId, adminView, all);
        budget.setServerId(server.getId());
        budget.setServerName(server.getName());
        budget.setAccessMode(ToolExposureModes.accessOf(server));
        budget.setExposureMode(ToolExposureModes.exposureOf(server));
        budget.setTotalTools(all.size());
        budget.setVisibleTools(visible.size());
        budget.setHotTools(hotTools(server, visible).size());
        budget.setWarnThreshold(resolveWarnThreshold(server));
        budget.setMaxThreshold(resolveMaxThreshold(server));
        budget.setOverWarn(visible.size() > budget.getWarnThreshold());
        budget.setOverMax(visible.size() > budget.getMaxThreshold());
        budget.setEstimatedTokens(estimateTokens(visible));

        if (assetAccessPolicy != null) {
            List<com.mcp.transfer.entity.McpPrompt> prompts = assetAccessPolicy.allPrompts(server.getId());
            List<com.mcp.transfer.entity.McpPrompt> visiblePrompts =
                    assetAccessPolicy.filterPrompts(server, appId, adminView, prompts);
            List<com.mcp.transfer.entity.McpResource> resources = assetAccessPolicy.allResources(server.getId());
            List<com.mcp.transfer.entity.McpResource> visibleResources =
                    assetAccessPolicy.filterResources(server, appId, adminView, resources);
            budget.setPromptAccessMode(ToolExposureModes.promptAccessOf(server));
            budget.setResourceAccessMode(ToolExposureModes.resourceAccessOf(server));
            budget.setTotalPrompts(prompts.size());
            budget.setVisiblePrompts(visiblePrompts.size());
            budget.setTotalResources(resources.size());
            budget.setVisibleResources(visibleResources.size());
            budget.setPromptWarnThreshold(budget.getWarnThreshold());
            budget.setResourceWarnThreshold(budget.getWarnThreshold());
            budget.setPromptOverWarn(visiblePrompts.size() > budget.getWarnThreshold());
            budget.setResourceOverWarn(visibleResources.size() > budget.getWarnThreshold());
            budget.setPromptEstimatedTokens(estimateAssetTokens(visiblePrompts));
            budget.setResourceEstimatedTokens(estimateAssetTokens(visibleResources));
        }
        return budget;
    }

    public int resolveWarnThreshold(McpServer server) {
        Integer override = server == null ? null : server.getToolWarnThreshold();
        int value = override != null && override > 0 ? override : properties.getWarnThreshold();
        return Math.max(1, value);
    }

    public int resolveMaxThreshold(McpServer server) {
        Integer override = server == null ? null : server.getToolMaxThreshold();
        int warn = resolveWarnThreshold(server);
        int value = override != null && override > 0 ? override : properties.getMaxThreshold();
        return Math.max(warn, value);
    }

    private long estimateAssetTokens(List<?> assets) {
        long tokens = 0;
        for (Object asset : assets) {
            Integer estimate = null;
            String json = null;
            if (asset instanceof com.mcp.transfer.entity.McpPrompt) {
                estimate = ((com.mcp.transfer.entity.McpPrompt) asset).getTokenEstimate();
                json = ((com.mcp.transfer.entity.McpPrompt) asset).getResJson();
            } else if (asset instanceof com.mcp.transfer.entity.McpResource) {
                estimate = ((com.mcp.transfer.entity.McpResource) asset).getTokenEstimate();
                json = ((com.mcp.transfer.entity.McpResource) asset).getResJson();
            }
            tokens += estimate != null && estimate > 0 ? estimate : tokenEstimator.estimate(json) + 30L;
        }
        return tokens;
    }

    /**
     * 粗略估算 tools/list 定义的 token 数：按 JSON 字符数 / 4 计，另加每个工具的固定结构开销。
     * 只用于管理台排序与告警，不作为计费依据。
     */
    private long estimateTokens(List<McpTool> tools) {
        long tokens = 0;
        for (McpTool tool : tools) {
            if (tool.getTokenEstimate() != null && tool.getTokenEstimate() > 0) {
                tokens += tool.getTokenEstimate();
            } else {
                tokens += tokenEstimator.estimate(tool.getResJson()) + 30L;
            }
        }
        return tokens;
    }

}
