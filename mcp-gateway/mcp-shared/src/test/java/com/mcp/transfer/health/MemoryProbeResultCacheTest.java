package com.mcp.transfer.health;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MemoryProbeResultCacheTest {

    private final MemoryProbeResultCache cache = new MemoryProbeResultCache();

    @Test
    void storesAndReadsSnapshot() {
        cache.put(1, new ProbeSnapshot(ProbeResult.ok(12, "ok"), System.currentTimeMillis()));

        ProbeSnapshot snapshot = cache.get(1);
        assertNotNull(snapshot);
        assertEquals(true, snapshot.result.ok);
        assertEquals(12, snapshot.result.latencyMs);
        assertFalse(cache.shared());
    }

    @Test
    void missingReturnsNull() {
        assertNull(cache.get(2));
        assertNull(cache.get(null));
    }

    @Test
    void nullPutIsIgnored() {
        cache.put(null, new ProbeSnapshot(ProbeResult.ok(1, "x"), System.currentTimeMillis()));
        cache.put(3, null);
        assertNull(cache.get(3));
    }
}
