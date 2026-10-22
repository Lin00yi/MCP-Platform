package com.mcp.transfer.exposure;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolSynonymsTest {

    @Test
    void expandsChineseToEnglish() {
        List<String> values = ToolSynonyms.of("订单");
        assertTrue(values.contains("order"));
        assertTrue(values.contains("orders"));
    }

    @Test
    void expandsEnglishToChinese() {
        List<String> values = ToolSynonyms.of("refund");
        assertTrue(values.contains("退款"));
    }

    @Test
    void chineseOfEnglishToken() {
        assertTrue(ToolSynonyms.chineseOf("order").contains("订单"));
        assertTrue(ToolSynonyms.chineseOf("unknown-xyz").isEmpty());
    }

    @Test
    void expandIntoDeduplicates() {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        ToolSynonyms.expandInto("订单", terms);
        ToolSynonyms.expandInto("订单", terms);
        assertTrue(terms.contains("订单"));
        assertTrue(terms.contains("order"));
    }
}
