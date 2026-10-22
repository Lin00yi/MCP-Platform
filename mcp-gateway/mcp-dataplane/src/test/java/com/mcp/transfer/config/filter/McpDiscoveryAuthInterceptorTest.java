package com.mcp.transfer.config.filter;

import com.mcp.transfer.identity.UserIdentity;
import com.mcp.transfer.identity.UserTokenProperties;
import com.mcp.transfer.identity.UserTokenVerifier;
import com.mcp.transfer.utils.SecretCrypto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpDiscoveryAuthInterceptorTest {

    @Mock
    private UserTokenVerifier userTokenVerifier;

    private McpDiscoveryAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        UserTokenProperties properties = new UserTokenProperties();
        properties.setHeader("x-dsh-auth-token");

        UserTokenAuthenticator authenticator = new UserTokenAuthenticator();
        ReflectionTestUtils.setField(authenticator, "userTokenVerifier", userTokenVerifier);
        ReflectionTestUtils.setField(authenticator, "userTokenProperties", properties);
        ReflectionTestUtils.setField(authenticator, "secretCrypto", new SecretCrypto());

        interceptor = new McpDiscoveryAuthInterceptor();
        ReflectionTestUtils.setField(interceptor, "userTokenAuthenticator", authenticator);
    }

    @Test
    void missingTokenReturns401WithChallenge() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp/discovery/servers");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
        assertTrue(response.getHeader("WWW-Authenticate").contains("error=\"invalid_request\""));
        verifyNoInteractions(userTokenVerifier);
    }

    @Test
    void invalidTokenReturns401WithInvalidTokenChallenge() throws Exception {
        when(userTokenVerifier.verify("dsh_bad")).thenReturn(null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp/discovery/servers");
        request.addHeader("x-dsh-auth-token", "dsh_bad");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
        assertTrue(response.getHeader("WWW-Authenticate").contains("error=\"invalid_token\""));
    }

    @Test
    void validTokenIsAllowed() throws Exception {
        UserIdentity user = new UserIdentity();
        user.setUserId("u-1");
        when(userTokenVerifier.verify("dsh_ok")).thenReturn(user);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp/discovery/servers");
        request.addHeader("x-dsh-auth-token", "dsh_ok");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals("u-1", request.getAttribute(McpDiscoveryAuthInterceptor.DISCOVERY_USER_ID_ATTR));
    }
}
