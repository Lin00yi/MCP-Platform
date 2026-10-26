package com.mcp.transfer.topology;

import com.mcp.transfer.shared.entity.McpEndpoint;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Endpoint 的运行时范围：允许访问的 Server 集合 + 每个 Server 使用的工具名前缀。
 *
 * <p>由 {@link EndpointScopeReader#resolveScope} 产出，数据面据此收窄可见工具，
 * 不包含任何写操作，可安全跨模块/跨服务传递。
 *
 * <p>该对象可能被 {@link EndpointScopeReader} 的进程内缓存复用，因此内部集合一律不可变，
 * 调用方只读即可。
 */
public final class EndpointScope {

    private final McpEndpoint endpoint;
    private final Map<Integer, String> prefixByServer;
    private final String path;

    public EndpointScope(McpEndpoint endpoint, Map<Integer, String> prefixByServer) {
        this.endpoint = endpoint;
        this.prefixByServer = prefixByServer == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(prefixByServer);
        this.path = EndpointPaths.of(endpoint.getEndpointKey());
    }

    public McpEndpoint getEndpoint() {
        return endpoint;
    }

    public Map<Integer, String> getPrefixByServer() {
        return prefixByServer;
    }

    public Set<Integer> getServerIds() {
        return prefixByServer.keySet();
    }

    public String getPath() {
        return path;
    }
}
