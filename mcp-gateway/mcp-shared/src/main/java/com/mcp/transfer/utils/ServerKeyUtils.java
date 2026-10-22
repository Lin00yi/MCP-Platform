package com.mcp.transfer.utils;

import java.util.Locale;

/**
 * Server 稳定标识（DSH serverName / 工具前缀）生成规则。
 *
 * <p>要求与 DSH MCP client 的 serverName 约束一致：{@code [A-Za-z0-9_-]{1,32}}。
 * server_key 一旦生成不可变；后台改名不能影响工具名。
 */
public final class ServerKeyUtils {

    private static final int MAX_LENGTH = 32;

    private ServerKeyUtils() {
    }

    /** name → 小写 slug；非法字符折叠为 '-'，去掉首尾 '-'。 */
    public static String slug(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "server";
        }
        String s = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (s.isEmpty()) {
            s = "server";
        }
        if (s.length() > MAX_LENGTH) {
            s = s.substring(0, MAX_LENGTH).replaceAll("-+$", "");
        }
        return s.isEmpty() ? "server" : s;
    }

    /** 冲突时追加 id，并保证总长度不超过 32。 */
    public static String withId(String base, Integer id) {
        String suffix = "-" + id;
        String prefix = base == null ? "server" : base;
        if (prefix.length() + suffix.length() > MAX_LENGTH) {
            prefix = prefix.substring(0, MAX_LENGTH - suffix.length()).replaceAll("-+$", "");
        }
        return prefix + suffix;
    }

    /** 读取旧数据时的兜底：server_key 为空则用 name-id。 */
    public static String fallback(String name, Integer id) {
        return withId(slug(name), id);
    }

    public static boolean isValid(String key) {
        return key != null && key.matches("[A-Za-z0-9_-]{1,32}");
    }
}
