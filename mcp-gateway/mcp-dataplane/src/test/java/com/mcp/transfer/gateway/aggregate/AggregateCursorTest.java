package com.mcp.transfer.gateway.aggregate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AggregateCursorTest {

    @Test
    void roundTripsOffset() {
        String cursor = AggregateCursor.encode(200);
        assertEquals(200, AggregateCursor.decode(cursor));
    }

    @Test
    void invalidOrEmptyCursorStartsFromZero() {
        assertEquals(0, AggregateCursor.decode(null));
        assertEquals(0, AggregateCursor.decode(""));
        assertEquals(0, AggregateCursor.decode("not-a-cursor"));
        assertEquals(0, AggregateCursor.decode(AggregateCursor.encode(0)));
    }
}
