package com.mcp.transfer.exposure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Prompt / Resource 列表游标分页，复用 tools/list 的 cursor 编码。
 */
public final class AssetPager {

    private AssetPager() {
    }

    public static <T> Page<T> page(List<T> items, String cursor, int pageSize, Function<T, Integer> idGetter) {
        int size = pageSize > 0 ? pageSize : 500;
        List<T> sorted = new ArrayList<>();
        if (items != null) {
            sorted.addAll(items);
        }
        sorted.sort(Comparator.comparing(idGetter, Comparator.nullsLast(Integer::compareTo)));

        Integer after = ToolPager.decode(cursor);
        int start = 0;
        if (after != null) {
            start = sorted.size();
            for (int i = 0; i < sorted.size(); i++) {
                Integer id = idGetter.apply(sorted.get(i));
                if (id != null && id > after) {
                    start = i;
                    break;
                }
            }
        }
        int end = Math.min(start + size, sorted.size());
        List<T> pageItems = start >= sorted.size()
                ? new ArrayList<>()
                : new ArrayList<>(sorted.subList(start, end));
        String nextCursor = end < sorted.size() && !pageItems.isEmpty()
                ? ToolPager.encode(idGetter.apply(pageItems.get(pageItems.size() - 1)))
                : null;

        Page<T> page = new Page<>();
        page.setItems(pageItems);
        page.setNextCursor(nextCursor);
        return page;
    }

    public static class Page<T> {
        private List<T> items;
        private String nextCursor;

        public List<T> getItems() {
            return items;
        }

        public void setItems(List<T> items) {
            this.items = items;
        }

        public String getNextCursor() {
            return nextCursor;
        }

        public void setNextCursor(String nextCursor) {
            this.nextCursor = nextCursor;
        }
    }
}
