package com.mcp.transfer.health;

/**
 * MCP Server 健康状态（组合「配置状态 + 主动探测 + 近 5 分钟调用成功率」得出）。
 */
public enum McpHealthStatus {
    /** 正常 */
    UP,
    /** 降级：探测部分失败或近期成功率偏低 */
    DEGRADED,
    /** 不可用：探测失败 */
    DOWN,
    /** 已下线（status=0） */
    DISABLED,
    /** 未知：尚未探测且无调用数据 */
    UNKNOWN
}
