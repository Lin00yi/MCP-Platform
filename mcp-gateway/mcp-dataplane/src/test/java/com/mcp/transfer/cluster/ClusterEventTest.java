package com.mcp.transfer.cluster;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterEventTest {

    @Test
    void jsonRoundTrip() {
        ClusterEvent event = ClusterEvent.of(ClusterEvent.Type.TOOL, 42);
        String json = event.toJson();

        ClusterEvent parsed = ClusterEvent.fromJson(json);
        assertNotNull(parsed);
        assertEquals(ClusterEvent.Type.TOOL, parsed.getType());
        assertEquals(Integer.valueOf(42), parsed.getServerId());
        assertEquals(event.getAt(), parsed.getAt());
    }

    @Test
    void nullServerIdRoundTrip() {
        ClusterEvent parsed = ClusterEvent.fromJson(ClusterEvent.of(ClusterEvent.Type.ROUTES, null).toJson());
        assertNotNull(parsed);
        assertEquals(ClusterEvent.Type.ROUTES, parsed.getType());
        assertEquals(null, parsed.getServerId());
    }

    @Test
    void toolEventCarriesToolIdAndOp() {
        ClusterEvent parsed = ClusterEvent.fromJson(ClusterEvent.tool(3, 99, "UPSERT").toJson());

        assertNotNull(parsed);
        assertEquals(ClusterEvent.Type.TOOL, parsed.getType());
        assertEquals(Integer.valueOf(3), parsed.getServerId());
        assertEquals(Integer.valueOf(99), parsed.getToolId());
        assertEquals("UPSERT", parsed.getOp());
    }

    @Test
    void localBusDispatchesToAllSubscribers() {
        LocalClusterEventBus bus = new LocalClusterEventBus();
        List<String> seen = new ArrayList<>();
        bus.subscribe(e -> seen.add("a:" + e.getType()));
        bus.subscribe(e -> seen.add("b:" + e.getType()));

        bus.publish(ClusterEvent.of(ClusterEvent.Type.PROMPT, 7));

        assertEquals(2, seen.size());
        assertEquals("a:PROMPT", seen.get(0));
        assertEquals("b:PROMPT", seen.get(1));
        assertFalse(bus.distributed());
    }

    @Test
    void localBusKeepsWorkingWhenOneSubscriberFails() {
        LocalClusterEventBus bus = new LocalClusterEventBus();
        List<String> seen = new ArrayList<>();
        bus.subscribe(e -> {
            throw new IllegalStateException("boom");
        });
        bus.subscribe(e -> seen.add("ok"));

        bus.publish(ClusterEvent.of(ClusterEvent.Type.RESOURCE, 1));

        assertEquals(1, seen.size());
        assertTrue(seen.contains("ok"));
    }
}
