package com.mcp.transfer.identity;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticUserTokenStoreTest {

    @Test
    void parsesMultipleTokensAndScopes() {
        StaticUserTokenStore store = new StaticUserTokenStore(
                "tok-a|u-1|张三|C-1|tool:read,tool:write;tok-b|u-2|李四|C-2|");

        assertEquals(2, store.size());
        UserIdentity a = store.find("tok-a");
        assertEquals("u-1", a.getUserId());
        assertEquals("C-1", a.getCustomerId());
        assertEquals(Arrays.asList("tool:read", "tool:write"), a.getScopes());
        assertTrue(store.find("tok-b").getScopes().isEmpty());
        assertNull(store.find("missing"));
    }

    @Test
    void ignoresMalformedEntries() {
        StaticUserTokenStore store = new StaticUserTokenStore("bad-entry;tok|u-1|name|C-1|read");
        assertEquals(1, store.size());
        assertEquals("u-1", store.find("tok").getUserId());
    }
}
