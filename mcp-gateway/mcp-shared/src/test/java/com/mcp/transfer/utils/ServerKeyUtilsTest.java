package com.mcp.transfer.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerKeyUtilsTest {

    @Test
    void slugNormalizesName() {
        assertEquals("pcb-server", ServerKeyUtils.slug("PCB Server"));
        assertEquals("pcb-server", ServerKeyUtils.slug("  PCB__Server  "));
        assertEquals("server", ServerKeyUtils.slug("中文名称"));
        assertEquals("server", ServerKeyUtils.slug(null));
    }

    @Test
    void slugTruncatesTo32() {
        String key = ServerKeyUtils.slug("a".repeat(40));
        assertEquals(32, key.length());
        assertTrue(ServerKeyUtils.isValid(key));
    }

    @Test
    void withIdKeepsWithin32() {
        String key = ServerKeyUtils.withId("a".repeat(30), 123);
        assertEquals(32, key.length());
        assertTrue(key.endsWith("-123"));
    }

    @Test
    void validatesDshServerName() {
        assertTrue(ServerKeyUtils.isValid("pcb-server_1"));
        assertFalse(ServerKeyUtils.isValid("中文"));
        assertFalse(ServerKeyUtils.isValid("a".repeat(33)));
    }
}
