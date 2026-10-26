package com.mcp.transfer.port;

import com.mcp.transfer.shared.entity.McpTool;

import java.util.Map;

/**
 * 工具连通性探测端口（控制面 → 数据面）。
 *
 * <p>管理台「测试连通性」需要按工具的 auth 配置真实发一次请求，
 * 这段逻辑天然属于数据面（上游出网、SSRF 校验、sk 换取 token）。
 * 控制面只依赖本接口，避免注入 {@code McpService}。
 */
public interface ToolConnectivityProbe {

    /**
     * @return {@code {ok, status?, body?, note?, error?, duration}}
     */
    Map<String, Object> test(McpTool tool, String authorization);
}
