package com.mcp.transfer.exposure;

import com.mcp.transfer.shared.entity.McpTool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ToolPagerTest {

    private List<McpTool> tools() {
        List<McpTool> tools = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            tools.add(new McpTool().setId(i).setCode("tool-" + i));
        }
        return tools;
    }

    @Test
    void pagesThroughAllToolsWithoutDuplicatesOrGaps() {
        ToolPager.Page first = ToolPager.page(tools(), null, 2);
        assertEquals(Arrays.asList(1, 2), ids(first.getTools()));
        assertNotNull(first.getNextCursor());

        ToolPager.Page second = ToolPager.page(tools(), first.getNextCursor(), 2);
        assertEquals(Arrays.asList(3, 4), ids(second.getTools()));

        ToolPager.Page third = ToolPager.page(tools(), second.getNextCursor(), 2);
        assertEquals(Arrays.asList(5), ids(third.getTools()));
        assertNull(third.getNextCursor());
    }

    @Test
    void smallResultHasNoCursor() {
        ToolPager.Page page = ToolPager.page(tools(), null, 200);
        assertEquals(5, page.getTools().size());
        assertNull(page.getNextCursor());
    }

    @Test
    void invalidCursorFallsBackToFirstPage() {
        ToolPager.Page page = ToolPager.page(tools(), "not-a-cursor", 2);
        assertEquals(Arrays.asList(1, 2), ids(page.getTools()));
    }

    @Test
    void cursorRoundTrip() {
        String cursor = ToolPager.encode(42);
        assertEquals(Integer.valueOf(42), ToolPager.decode(cursor));
        assertEquals(Integer.valueOf(7), ToolPager.decode("7"));
        assertNull(ToolPager.decode(null));
        assertNull(ToolPager.decode(""));
    }

    private List<Integer> ids(List<McpTool> tools) {
        List<Integer> ids = new ArrayList<>();
        for (McpTool tool : tools) {
            ids.add(tool.getId());
        }
        return ids;
    }
}
