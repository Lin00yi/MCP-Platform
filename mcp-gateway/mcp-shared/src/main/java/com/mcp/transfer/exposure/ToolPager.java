package com.mcp.transfer.exposure;

import com.mcp.transfer.entity.McpTool;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

/**
 * tools/list 游标分页（MCP 标准 cursor/nextCursor）。
 *
 * <p>只解决“响应太大传不动/客户端解析慢”，不负责相关性过滤；
 * cursor 对客户端不透明，内部按 mcp_tool.id 递增定位，工具增删时依然稳定。
 * 注意：部分客户端不会跟随 nextCursor，因此 pageSize 默认给得较大（200），
 * 仅在工具数超过 pageSize 时才真正分页。
 */
public final class ToolPager {

    private static final String PREFIX = "v1:";

    private ToolPager() {
    }

    public static String encode(Integer lastId) {
        if (lastId == null) {
            return null;
        }
        String raw = PREFIX + lastId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 解析 cursor；null/空/非法均返回 null（按第一页处理，避免客户端因脏 cursor 卡死） */
    public static Integer decode(String cursor) {
        if (cursor == null || cursor.trim().isEmpty()) {
            return null;
        }
        String value = cursor.trim();
        if (value.matches("\\d+")) {
            try {
                return Integer.valueOf(value);
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            if (!raw.startsWith(PREFIX)) {
                return null;
            }
            return Integer.valueOf(raw.substring(PREFIX.length()));
        } catch (Exception e) {
            return null;
        }
    }

    public static Page page(List<McpTool> tools, String cursor, int pageSize) {
        int size = pageSize > 0 ? pageSize : 200;
        List<McpTool> sorted = new ArrayList<>();
        if (tools != null) {
            sorted.addAll(tools);
        }
        sorted.sort(Comparator.comparing(McpTool::getId, Comparator.nullsLast(Integer::compareTo)));

        Integer after = decode(cursor);
        int start = 0;
        if (after != null) {
            start = sorted.size();
            for (int i = 0; i < sorted.size(); i++) {
                Integer id = sorted.get(i).getId();
                if (id != null && id > after) {
                    start = i;
                    break;
                }
            }
        }
        int end = Math.min(start + size, sorted.size());
        List<McpTool> pageTools = start >= sorted.size()
                ? new ArrayList<>()
                : new ArrayList<>(sorted.subList(start, end));
        String nextCursor = end < sorted.size() && !pageTools.isEmpty()
                ? encode(pageTools.get(pageTools.size() - 1).getId())
                : null;

        Page page = new Page();
        page.setTools(pageTools);
        page.setNextCursor(nextCursor);
        return page;
    }

    public static class Page {
        private List<McpTool> tools;
        private String nextCursor;

        public List<McpTool> getTools() {
            return tools;
        }

        public void setTools(List<McpTool> tools) {
            this.tools = tools;
        }

        public String getNextCursor() {
            return nextCursor;
        }

        public void setNextCursor(String nextCursor) {
            this.nextCursor = nextCursor;
        }
    }
}
