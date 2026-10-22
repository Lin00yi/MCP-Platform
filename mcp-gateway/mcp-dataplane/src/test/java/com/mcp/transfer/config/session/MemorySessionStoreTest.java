package com.mcp.transfer.config.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemorySessionStoreTest {

    private final MemorySessionStore store = new MemorySessionStore();

    @Test
    void savesAndFindsSession() {
        SessionInfo info = new SessionInfo(System.currentTimeMillis() + 60_000);
        info.setClientName("codex");
        store.save("s1", info, 60_000);

        SessionInfo found = store.find("s1");
        assertNotNull(found);
        assertEquals("codex", found.getClientName());
        assertEquals(1, store.size());
        assertFalse(store.shared());
    }

    @Test
    void expiredSessionIsNotReturned() {
        SessionInfo info = new SessionInfo(System.currentTimeMillis() - 1);
        store.save("expired", info, 60_000);

        assertNull(store.find("expired"));
        assertEquals(0, store.size());
    }

    @Test
    void removeAndNullSafety() {
        store.save("s2", new SessionInfo(System.currentTimeMillis() + 60_000), 60_000);
        store.remove("s2");
        assertNull(store.find("s2"));

        store.remove(null);
        assertNull(store.find(null));
        assertNull(store.find(""));
    }

    @Test
    void disabledCapacityGuardDoesNotDropSessions() {
        // max-count 未注入（=0）时不应触发淘汰
        for (int i = 0; i < 50; i++) {
            store.save("k" + i, new SessionInfo(System.currentTimeMillis() + 60_000), 60_000);
        }
        assertEquals(50, store.size());
        assertTrue(store.find("k49") != null);
    }
}
