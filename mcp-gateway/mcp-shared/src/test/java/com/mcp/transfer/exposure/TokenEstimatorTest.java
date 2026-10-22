package com.mcp.transfer.exposure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenEstimatorTest {

    private final TokenEstimator estimator = new TokenEstimator();

    @Test
    void chineseCharsCountAsSingleTokens() {
        assertEquals(4, estimator.estimate("订单查询"));
    }

    @Test
    void asciiUsesFourCharsPerToken() {
        assertEquals(2, estimator.estimate("abcdefgh"));
        assertEquals(1, estimator.estimate("abc"));
    }

    @Test
    void mixedTextIsBetweenCharAndWordCount() {
        int tokens = estimator.estimate("查询 order 详情");
        assertTrue(tokens >= 4 && tokens <= 10, "tokens=" + tokens);
        assertEquals("cjk-aware/approx-v1", estimator.method());
    }

    @Test
    void emptyIsZero() {
        assertEquals(0, estimator.estimate(null));
        assertEquals(0, estimator.estimate(""));
    }
}
