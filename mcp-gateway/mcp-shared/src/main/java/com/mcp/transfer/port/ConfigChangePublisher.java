package com.mcp.transfer.port;

/**
 * 配置变更发布端口（控制面 → 数据面）。
 *
 * <p>控制面（S2）在写入 Server / Tool / Prompt / Resource / 拓扑后调用本端口，
 * 由数据面（S1）负责失效本地缓存、刷新动态路由、并向已连接的 SSE 客户端广播
 * {@code notifications/tools|prompts|resources/list_changed}。
 *
 * <p>同进程部署时由 {@code LocalConfigChangePublisher} 实现（见 {@code gateway.port}）；
 * 物理拆分后由远程实现（HTTP / Redis）承接。控制面只依赖本接口，
 * <b>不得</b>再直接引用数据面的任何类。
 */
public interface ConfigChangePublisher {

    /** 工具定义变更：失效 tools/list 缓存 + 广播 list_changed（整 server 粒度） */
    void toolChanged(Integer serverId);

    /**
     * 工具级变更（更精确，用于向量索引增量维护与库内去重）。
     *
     * @param toolId 具体工具；为 null 表示「该 server 下的工具集合变了」，由数据面整体重建
     * @param op     {@code UPSERT} / {@code DELETE} / {@code PURGE_SERVER}；null 表示未指定
     */
    default void toolChanged(Integer serverId, Integer toolId, String op) {
        toolChanged(serverId);
    }

    /** Prompt 变更 */
    void promptChanged(Integer serverId);

    /** Resource 变更 */
    void resourceChanged(Integer serverId);

    /** Server 路由变更：重新注册 / 清理动态端点 */
    void routesChanged();
}
