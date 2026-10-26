package com.mcp.transfer.cache;

import com.mcp.transfer.shared.entity.McpTool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * tools/list 结果缓存（按 serverId）。工具增删改时由管理端主动失效，保证与数据库一致。
 */
@Component
public class ToolListCache {

    private final Map<Integer, List<McpTool>> cache = new ConcurrentHashMap<>();

    public List<McpTool> get(Integer serverId) {
        return cache.get(serverId);
    }

    public void put(Integer serverId, List<McpTool> tools) {
        if (serverId != null && tools != null) {
            cache.put(serverId, tools);
        }
    }

    public void invalidate(Integer serverId) {
        if (serverId != null) {
            cache.remove(serverId);
        }
    }

    public void clear() {
        cache.clear();
    }
}
