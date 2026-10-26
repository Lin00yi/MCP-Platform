package com.mcp.transfer.shared.config.security;

import java.util.Arrays;
import java.util.List;

/**
 * MCP Server 对外路径校验：防止动态路由覆盖管理接口、静态资源或 Spring 内部端点。
 *
 * <p>动态注册的 path 会直接变成 Spring MVC 的 RequestMapping，因此必须严格限制字符集与保留前缀。
 */
public final class McpPathValidator {

    /** 保留前缀：动态 MCP 端点不得占用 */
    private static final List<String> RESERVED_PREFIXES = Arrays.asList(
            "/mcp/admin",
            "/mcp/log",
            "/mcp/discovery",
            "/mcp/e",
            "/v3",
            "/swagger-ui",
            "/swagger-resources",
            "/error",
            "/actuator",
            "/api",
            "/favicon.ico"
    );

    private static final int MAX_LENGTH = 200;

    private McpPathValidator() {
    }

    /**
     * 校验并规范化路径。
     *
     * @return 规范化后的路径（去掉末尾 /、折叠 //）
     * @throws IllegalArgumentException 校验失败，message 可直接返回给前端
     */
    public static String validateAndNormalize(String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            throw new IllegalArgumentException("路径 path 不能为空");
        }
        String path = rawPath.trim();

        // 折叠重复斜杠
        while (path.contains("//")) {
            path = path.replace("//", "/");
        }
        // 去掉末尾斜杠（根路径除外）
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("路径 path 必须以 / 开头");
        }
        if (path.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("路径 path 长度不能超过 " + MAX_LENGTH);
        }
        if ("/".equals(path)) {
            throw new IllegalArgumentException("路径 path 不能为根路径 /");
        }
        if (!path.matches("^/[A-Za-z0-9._~/-]+$")) {
            throw new IllegalArgumentException("路径 path 仅允许字母、数字、. _ ~ / - 且不能包含空白或特殊字符");
        }
        for (String segment : path.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("路径 path 不能包含 . 或 .. 段");
            }
        }
        for (String prefix : RESERVED_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                throw new IllegalArgumentException("路径 path 不能占用系统保留前缀：" + prefix);
            }
        }
        return path;
    }

    /** 是否为保留前缀（供动态路由刷新时做防御性跳过） */
    public static boolean isReserved(String path) {
        if (path == null) {
            return true;
        }
        for (String prefix : RESERVED_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }
}
