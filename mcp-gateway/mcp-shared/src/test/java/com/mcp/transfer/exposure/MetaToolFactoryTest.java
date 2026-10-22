package com.mcp.transfer.exposure;

import com.mcp.transfer.dto.mcp.result.tools.McpTool;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetaToolFactoryTest {

    @Test
    void buildsFourMetaToolsWithValidSchemas() {
        List<McpTool> tools = MetaToolFactory.build();

        assertEquals(4, tools.size());
        Set<String> names = new HashSet<>();
        for (McpTool tool : tools) {
            names.add(tool.getName());
            assertNotNull(tool.getDescription());
            assertNotNull(tool.getInputSchema());
            assertEquals("object", tool.getInputSchema().getString("type"));
            assertNotNull(tool.getInputSchema().getJSONObject("properties"));
        }
        assertTrue(names.contains(MetaToolFactory.SEARCH_TOOLS));
        assertTrue(names.contains(MetaToolFactory.GET_TOOL_SCHEMA));
        assertTrue(names.contains(MetaToolFactory.CALL_TOOL));
        assertTrue(names.contains(MetaToolFactory.READ_RESULT_CHUNK));
    }

    @Test
    void searchToolSchemaMakesQueryOptional() {
        McpTool search = null;
        for (McpTool tool : MetaToolFactory.build()) {
            if (MetaToolFactory.SEARCH_TOOLS.equals(tool.getName())) {
                search = tool;
            }
        }
        assertNotNull(search);
        assertNotNull(search.getInputSchema().getJSONObject("properties").getJSONObject("domain"));
        assertNotNull(search.getInputSchema().getJSONObject("properties").getJSONObject("tags"));
        assertNotNull(search.getInputSchema().getJSONObject("properties").getJSONObject("top_k"));
        // 目录模式要求 query 可为空，因此不应把 query 设为必填
        assertTrue(search.getInputSchema().getJSONArray("required") == null
                || !search.getInputSchema().getJSONArray("required").contains("query"));
    }

    @Test
    void callToolSchemaAcceptsNameAndArguments() {
        McpTool call = null;
        for (McpTool tool : MetaToolFactory.build()) {
            if (MetaToolFactory.CALL_TOOL.equals(tool.getName())) {
                call = tool;
            }
        }
        assertNotNull(call);
        assertEquals("object", call.getInputSchema()
                .getJSONObject("properties").getJSONObject("arguments").getString("type"));
        assertEquals("name", call.getInputSchema().getJSONArray("required").getString(0));
        assertNotNull(call.getInputSchema().getJSONObject("properties").getJSONObject("reason"));
    }

    @Test
    void recognisesOnlyMetaNames() {
        assertTrue(MetaToolFactory.isMetaTool(MetaToolFactory.SEARCH_TOOLS));
        assertTrue(MetaToolFactory.isMetaTool(MetaToolFactory.CALL_TOOL));
        assertTrue(!MetaToolFactory.isMetaTool("order_query"));
        assertTrue(!MetaToolFactory.isMetaTool(null));
    }
}
