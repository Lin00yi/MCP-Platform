package com.mcp.transfer.shared.utils;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录限流分桶用的客户端 IP 解析：只信任 X-Real-IP（合法 IP 字面量），忽略可伪造的 X-Forwarded-For。
 */
class IpUtilsTest {

    @Test
    void xForwardedForIsIgnored() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        assertEquals("10.0.0.9", IpUtils.clientIp(request));
    }

    @Test
    void xRealIpIsTrustedWhenValid() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");
        request.addHeader("X-Real-IP", " 203.0.113.7 ");
        assertEquals("203.0.113.7", IpUtils.clientIp(request));
    }

    @Test
    void invalidXRealIpFallsBackToRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");
        request.addHeader("X-Real-IP", "not-an-ip");
        assertEquals("10.0.0.9", IpUtils.clientIp(request));

        MockHttpServletRequest multi = new MockHttpServletRequest();
        multi.setRemoteAddr("10.0.0.8");
        multi.addHeader("X-Real-IP", "1.2.3.4, 5.6.7.8");
        assertEquals("10.0.0.8", IpUtils.clientIp(multi));
    }

    @Test
    void ipLiteralValidation() {
        assertTrue(IpUtils.isValidIpLiteral("203.0.113.7"));
        assertTrue(IpUtils.isValidIpLiteral("2001:db8::1"));
        assertTrue(IpUtils.isValidIpLiteral("::1"));
        assertTrue(IpUtils.isValidIpLiteral("[2001:db8::1]"));
        assertFalse(IpUtils.isValidIpLiteral("example.com"));
        assertFalse(IpUtils.isValidIpLiteral("1.2.3.4.5"));
        assertFalse(IpUtils.isValidIpLiteral(""));
        assertFalse(IpUtils.isValidIpLiteral(null));
    }

    @Test
    void nullRequestReturnsUnknown() {
        assertEquals("unknown", IpUtils.clientIp(null));
    }
}
