package com.mcp.transfer.gateway.session;

/**
 * 上游 MCP 会话映射存储：网关会话 → 下游 {@code Mcp-Session-Id}。
 *
 * <p>单副本用进程内实现；多副本用 Redis（Hash：一个网关会话一个 key），
 * 这样任一副本都能复用同一个上游会话，避免每次请求都重新 initialize。
 */
public interface UpstreamSessionStore {

    /** 取上游会话 id；不存在/已过期返回 null */
    String get(String gatewaySessionKey, String upstreamKey);

    void put(String gatewaySessionKey, String upstreamKey, String upstreamSessionId, long ttlMillis);

    /** 网关会话终止：清掉它名下所有上游会话 */
    void remove(String gatewaySessionKey);

    int size();
}
