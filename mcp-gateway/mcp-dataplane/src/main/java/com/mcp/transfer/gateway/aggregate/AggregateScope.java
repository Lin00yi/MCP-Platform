package com.mcp.transfer.gateway.aggregate;

import java.util.Set;

/**
 * 统一入口的请求级范围（Endpoint 模式）。
 *
 * <p>Endpoint 请求会把「允许的 Server + 每个 Server 的工具前缀」放进 ThreadLocal，
 * 聚合服务在本次请求内按该范围过滤与命名；请求结束必须 clear。
 */
public final class AggregateScope {

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private AggregateScope() {
    }

    public static void set(Scope scope) {
        CURRENT.set(scope);
    }

    public static Scope get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static final class Scope {
        private final Set<Integer> serverIds;
        private final java.util.Map<Integer, String> prefixByServer;
        private final String path;
        private final String endpointName;

        public Scope(Set<Integer> serverIds, java.util.Map<Integer, String> prefixByServer,
                     String path, String endpointName) {
            this.serverIds = serverIds;
            this.prefixByServer = prefixByServer;
            this.path = path;
            this.endpointName = endpointName;
        }

        public Set<Integer> getServerIds() {
            return serverIds;
        }

        public java.util.Map<Integer, String> getPrefixByServer() {
            return prefixByServer;
        }

        public String getPath() {
            return path;
        }

        public String getEndpointName() {
            return endpointName;
        }
    }
}
