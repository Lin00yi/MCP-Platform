package com.mcp.transfer.cache;

import com.mcp.transfer.entity.McpPrompt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** prompt 列表缓存（按 serverId）；增删改时由通知器失效。 */
@Component
public class PromptListCache {
    private final Map<Integer, List<McpPrompt>> cache = new ConcurrentHashMap<>();

    public List<McpPrompt> get(Integer serverId) {
        return cache.get(serverId);
    }

    public void put(Integer serverId, List<McpPrompt> prompts) {
        if (serverId != null && prompts != null) {
            cache.put(serverId, prompts);
        }
    }

    public void clear() {
        cache.clear();
    }

    public void invalidate(Integer serverId) {
        if (serverId != null) {
            cache.remove(serverId);
        }
    }
}
