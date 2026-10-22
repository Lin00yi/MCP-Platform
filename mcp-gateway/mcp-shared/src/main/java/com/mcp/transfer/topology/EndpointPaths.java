package com.mcp.transfer.topology;

/**
 * Endpoint 数据面路径常量。
 *
 * <p>必须是编译期常量：{@code McpEndpointController} 的 {@code @RequestMapping} 依赖它。
 */
public final class EndpointPaths {

    /** Endpoint 数据面前缀：{@code /mcp/e/{endpointKey}} */
    public static final String PREFIX = "/mcp/e/";

    private EndpointPaths() {
    }

    public static String of(String endpointKey) {
        return PREFIX + endpointKey;
    }
}
