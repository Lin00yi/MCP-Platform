package com.mcp.transfer.health;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpstreamCircuitBreakerTest {

    private UpstreamCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        breaker = new UpstreamCircuitBreaker();
        ReflectionTestUtils.setField(breaker, "failureThreshold", 3);
        ReflectionTestUtils.setField(breaker, "openSeconds", 60);
    }

    @Test
    void opensAfterConsecutiveFailures() {
        assertTrue(breaker.allow(1));
        breaker.recordFailure(1);
        breaker.recordFailure(1);
        assertTrue(breaker.allow(1));
        assertFalse(breaker.isOpen(1));

        breaker.recordFailure(1);

        assertTrue(breaker.isOpen(1));
        assertFalse(breaker.allow(1));
        assertTrue(breaker.reason(1).contains("熔断"));
    }

    @Test
    void successClosesCircuit() {
        breaker.recordFailure(1);
        breaker.recordFailure(1);
        breaker.recordFailure(1);
        assertTrue(breaker.isOpen(1));

        breaker.recordSuccess(1);

        assertFalse(breaker.isOpen(1));
        assertTrue(breaker.allow(1));
    }

    @Test
    void resetClearsState() {
        breaker.recordFailure(1);
        breaker.recordFailure(1);
        breaker.recordFailure(1);
        breaker.reset(1);
        assertTrue(allowAndNotOpen(1));
    }

    @Test
    void circuitIsPerServer() {
        breaker.recordFailure(1);
        breaker.recordFailure(1);
        breaker.recordFailure(1);
        assertTrue(breaker.isOpen(1));
        assertTrue(allowAndNotOpen(2));
    }

    @Test
    void zeroThresholdDisablesCircuit() {
        ReflectionTestUtils.setField(breaker, "failureThreshold", 0);
        breaker.recordFailure(1);
        breaker.recordFailure(1);
        breaker.recordFailure(1);
        assertTrue(breaker.allow(1));
        assertFalse(breaker.isOpen(1));
    }

    private boolean allowAndNotOpen(Integer serverId) {
        return breaker.allow(serverId) && !breaker.isOpen(serverId);
    }
}
