package com.mcp.transfer.cache;

import com.mcp.transfer.shared.entity.McpResource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** resource 列表缓存（按 serverId）；增删改时由通知器失效。 */
@Component
public class ResourceListCache {
    private final Map<Integer, List<McpResource>> cache = new ConcurrentHashMap<>();

    public List<McpResource> get(Integer serverId) {
        return cache.get(serverId);
    }

    public void put(Integer serverId, List<McpResource> resources) {
        if (serverId != null && resources != null) {
            cache.put(serverId, resources);
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
