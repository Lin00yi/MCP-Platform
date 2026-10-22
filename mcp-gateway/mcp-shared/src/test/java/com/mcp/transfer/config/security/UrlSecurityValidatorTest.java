package com.mcp.transfer.config.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UrlSecurityValidatorTest {

    private final UrlSecurityValidator validator = new UrlSecurityValidator();

    @Test
    void blocksLoopbackAndMetadata() {
        assertThrows(SecurityException.class, () -> validator.validate("http://127.0.0.1/x"));
        assertThrows(SecurityException.class, () -> validator.validate("http://localhost/x"));
        assertThrows(SecurityException.class, () -> validator.validate("http://169.254.169.254/latest/meta-data/"));
    }

    @Test
    void blocksPrivateAndCgnatRanges() {
        assertThrows(SecurityException.class, () -> validator.validate("http://10.1.2.3/"));
        assertThrows(SecurityException.class, () -> validator.validate("http://192.168.1.1/"));
        assertThrows(SecurityException.class, () -> validator.validate("http://172.16.0.1/"));
        assertThrows(SecurityException.class, () -> validator.validate("http://100.64.0.1/"));
        assertThrows(SecurityException.class, () -> validator.validate("http://[fd00::1]/"));
        assertThrows(SecurityException.class, () -> validator.validate("http://[::ffff:127.0.0.1]/"));
    }

    @Test
    void allowsPublicIp() {
        assertDoesNotThrow(() -> validator.validate("http://8.8.8.8/"));
        assertDoesNotThrow(() -> validator.validate("https://1.1.1.1/path"));
    }
}
