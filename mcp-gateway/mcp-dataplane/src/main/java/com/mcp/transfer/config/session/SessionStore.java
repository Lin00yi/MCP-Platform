package com.mcp.transfer.config.session;

/**
 * MCP 会话存储抽象。
 *
 * <p>两种实现：
 * <ul>
 *   <li>{@code MemorySessionStore}（默认）：进程内，单副本部署用；</li>
 *   <li>{@code RedisSessionStore}：多副本共享，任一副本都能校验同一会话。</li>
 * </ul>
 *
 * <p>TTL 由调用方按 {@code mcp.session} 的约定传入，存储层不关心语义。
 */
public interface SessionStore {

    void save(String sessionId, SessionInfo info, long ttlMillis);

    /** 不存在或已过期返回 null */
    SessionInfo find(String sessionId);

    void remove(String sessionId);

    int size();

    /** 是否为跨副本共享存储（用于日志/自检） */
    boolean shared();
}
