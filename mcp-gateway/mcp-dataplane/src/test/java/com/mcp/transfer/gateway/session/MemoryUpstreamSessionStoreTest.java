package com.mcp.transfer.gateway.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MemoryUpstreamSessionStoreTest {

    private final MemoryUpstreamSessionStore store = new MemoryUpstreamSessionStore();

    @Test
    void isolatesSessionsPerUpstream() {
        store.put("gw-1", "pcb", "up-pcb", 60_000);
        store.put("gw-1", "crm", "up-crm", 60_000);

        assertEquals("up-pcb", store.get("gw-1", "pcb"));
        assertEquals("up-crm", store.get("gw-1", "crm"));
        assertNull(store.get("gw-1", "other"));
        assertNull(store.get("gw-2", "pcb"));
    }

    @Test
    void singleUpstreamUsesDedicatedField() {
        store.put("gw-1", "__single__", "up-1", 60_000);
        assertEquals("up-1", store.get("gw-1", "__single__"));
        assertEquals(1, store.size());
    }

    @Test
    void removeClearsAllUpstreamsOfGatewaySession() {
        store.put("gw-1", "pcb", "up-pcb", 60_000);
        store.put("gw-1", "crm", "up-crm", 60_000);
        store.remove("gw-1");

        assertNull(store.get("gw-1", "pcb"));
        assertNull(store.get("gw-1", "crm"));
        assertEquals(0, store.size());
    }

    @Test
    void expiredEntryIsNotReturned() {
        store.put("gw-1", "pcb", "up-pcb", -1_000);
        assertNull(store.get("gw-1", "pcb"));
    }
}
