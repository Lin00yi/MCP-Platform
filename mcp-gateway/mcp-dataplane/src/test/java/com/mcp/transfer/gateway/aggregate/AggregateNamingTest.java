package com.mcp.transfer.gateway.aggregate;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AggregateNamingTest {

    @Test
    void exposesAndResolvesSimplePrefix() {
        String exposed = AggregateNaming.expose("pcb", "add");
        assertEquals("pcb__add", exposed);
        AggregateNaming.Resolved resolved = AggregateNaming.resolve(exposed, Collections.singleton("pcb"));
        assertEquals("pcb", resolved.getServerKey());
        assertEquals("add", resolved.getName());
    }

    @Test
    void resolvesLongestPrefixWhenServerKeyContainsSeparator() {
        String exposed = AggregateNaming.expose("a__b", "tool");
        assertEquals("a__b__tool", exposed);
        AggregateNaming.Resolved resolved = AggregateNaming.resolve(exposed, Arrays.asList("a", "a__b"));
        assertEquals("a__b", resolved.getServerKey());
        assertEquals("tool", resolved.getName());
    }

    @Test
    void returnsNullWhenNoKnownPrefix() {
        assertNull(AggregateNaming.resolve("unknown__tool", Collections.singleton("pcb")));
        assertNull(AggregateNaming.resolve("pcb__", Collections.singleton("pcb")));
        assertNull(AggregateNaming.resolve(null, Collections.singleton("pcb")));
    }

    @Test
    void keepsShortToolNameUnchanged() {
        assertEquals("pcb__add", AggregateNaming.exposeTool("pcb", "add"));
    }

    @Test
    void fitsLongToolNameWithinLimitAndKeepsPrefix() {
        String code = "very_long_tool_code_that_would_exceed_the_model_limit_for_tool_names";
        String exposed = AggregateNaming.exposeTool("pcb", code, 64);

        assertEquals(64, exposed.length());
        assertTrue(exposed.startsWith("pcb__"));
        assertTrue(exposed.matches("pcb__.*_[0-9a-f]{8}"));
        // 确定性：同一输入多次生成一致
        assertEquals(exposed, AggregateNaming.exposeTool("pcb", code, 64));
        // 前缀仍可解析，但后缀已被截断（调用时需按暴露名反查真实 code）
        AggregateNaming.Resolved resolved = AggregateNaming.resolve(exposed, Collections.singleton("pcb"));
        assertEquals("pcb", resolved.getServerKey());
        assertNotEquals(code, resolved.getName());
    }

    @Test
    void differentLongNamesGetDifferentHashes() {
        String a = AggregateNaming.exposeTool("pcb", "a".repeat(80), 64);
        String b = AggregateNaming.exposeTool("pcb", "b".repeat(80), 64);
        assertEquals(64, a.length());
        assertEquals(64, b.length());
        assertNotEquals(a, b);
    }

    @Test
    void zeroLimitDisablesFitting() {
        String code = "a".repeat(80);
        assertEquals("pcb__" + code, AggregateNaming.exposeTool("pcb", code, 0));
    }
}
