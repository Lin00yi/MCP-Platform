package com.mcp.transfer.exposure;

import com.mcp.transfer.entity.McpPrompt;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AssetPagerTest {

    @Test
    void pagesByCursor() {
        List<McpPrompt> prompts = Arrays.asList(
                new McpPrompt().setId(1), new McpPrompt().setId(2), new McpPrompt().setId(3));
        AssetPager.Page<McpPrompt> first = AssetPager.page(prompts, null, 2, McpPrompt::getId);
        assertEquals(2, first.getItems().size());
        assertNotNull(first.getNextCursor());

        AssetPager.Page<McpPrompt> second = AssetPager.page(prompts, first.getNextCursor(), 2, McpPrompt::getId);
        assertEquals(1, second.getItems().size());
        assertEquals(3, second.getItems().get(0).getId());
        assertNull(second.getNextCursor());
    }

    @Test
    void invalidCursorFallsBackToFirstPage() {
        List<McpPrompt> prompts = Arrays.asList(new McpPrompt().setId(1), new McpPrompt().setId(2));
        AssetPager.Page<McpPrompt> page = AssetPager.page(prompts, "bad-cursor", 2, McpPrompt::getId);
        assertEquals(2, page.getItems().size());
    }
}
