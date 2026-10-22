package com.mcp.transfer.config.session;

import java.util.Arrays;
import java.util.List;

/**
 * MCP 拦截器常量（会话头、过滤列表等）。
 */
public class McpInterceptorConstants {

    /** MCP Session-ID 请求头名称 */
    public static final String MCP_SESSION_ID_HEADER = "Mcp-Session-Id";

    /**
     * 无需校验 Session-ID 的方法列表（MCP 初始化方法）
     */
    public static final List<String> NO_CHECK_METHOD_LIST = Arrays.asList(
            "initialize",       // 简化版初始化方法
            "mcp/initialize"    // 标准初始化方法
    );
}
