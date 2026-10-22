package com.mcp.transfer.gateway.aggregate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 统一入口列表分页游标。
 *
 * <p>聚合结果是“多个 Server 的并集”，没有单一自增主键可用；
 * 这里用稳定排序后的偏移量作为游标。Server / 工具增删时游标可能漂移，
 * 与 MCP 的“不透明游标”语义一致，客户端只需原样回传。
 */
public final class AggregateCursor {

    private static final String PREFIX = "agg1:";
    private static final int MAX_OFFSET = 1_000_000;

    private AggregateCursor() {
    }

    public static String encode(int offset) {
        if (offset <= 0) {
            return null;
        }
        String raw = PREFIX + offset;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static int decode(String cursor) {
        if (cursor == null || cursor.trim().isEmpty()) {
            return 0;
        }
        String value = cursor.trim();
        if (value.matches("\\d+")) {
            return clamp(value);
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            if (!raw.startsWith(PREFIX)) {
                return 0;
            }
            return clamp(raw.substring(PREFIX.length()));
        } catch (Exception e) {
            return 0;
        }
    }

    private static int clamp(String value) {
        try {
            int offset = Integer.parseInt(value);
            return Math.max(0, Math.min(offset, MAX_OFFSET));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
