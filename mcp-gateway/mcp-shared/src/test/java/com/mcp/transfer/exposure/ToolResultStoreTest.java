package com.mcp.transfer.exposure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ToolResultStoreTest {

    private ToolExposureProperties properties;
    private ToolResultStore store;

    @BeforeEach
    void setUp() {
        properties = new ToolExposureProperties();
        properties.setChunkTtlSeconds(60);
        properties.setChunkCacheSize(10);
        store = new ToolResultStore();
        ReflectionTestUtils.setField(store, "properties", properties);
        // 默认 memory，避免单测依赖 Redis
        ReflectionTestUtils.setField(store, "chunkStore", "memory");
    }

    @Test
    void memoryRoundTrip() {
        String callId = store.put("app", "server:1", "hello", 5, false);

        ToolResultStore.Entry entry = store.get("app", callId);

        assertNotNull(entry);
        assertEquals("hello", entry.getText());
        assertEquals(5, entry.getTotalChars());
        assertEquals("server:1", entry.getScope());
    }

    @Test
    void appIdIsolation() {
        String callId = store.put("app-a", "server:1", "secret", 6, false);

        assertNull(store.get("app-b", callId));
        assertNotNull(store.get("app-a", callId));
    }

    @Test
    void unknownCallIdReturnsNull() {
        assertNull(store.get("app", "not-exists"));
        assertNull(store.get("app", null));
    }

    @Test
    void clearRemovesEntries() {
        String callId = store.put("app", "server:1", "text", 4, false);
        store.clear();
        assertNull(store.get("app", callId));
    }
}
