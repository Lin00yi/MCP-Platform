package com.mcp.transfer.config.filter;

import com.mcp.transfer.entity.McpServer;
import com.mcp.transfer.identity.McpIdentityContext;
import com.mcp.transfer.identity.UserIdentity;
import com.mcp.transfer.identity.UserTokenProperties;
import com.mcp.transfer.identity.UserTokenUnavailableException;
import com.mcp.transfer.identity.UserTokenVerifier;
import com.mcp.transfer.mapper.McpServerMapper;
import com.mcp.transfer.utils.SecretCrypto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpAuthInterceptorTest {

    private static final String PATH = "/mcp/v1/pcb-server";
    private static final String USER_TOKEN = "dsh_abc";

    @Mock
    private McpServerMapper mcpServerMapper;

    @Mock
    private UserTokenVerifier userTokenVerifier;

    private McpAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        UserTokenProperties properties = new UserTokenProperties();
        properties.setHeader("x-dsh-auth-token");

        UserTokenAuthenticator authenticator = new UserTokenAuthenticator();
        ReflectionTestUtils.setField(authenticator, "userTokenVerifier", userTokenVerifier);
        ReflectionTestUtils.setField(authenticator, "userTokenProperties", properties);
        ReflectionTestUtils.setField(authenticator, "secretCrypto", new SecretCrypto());

        interceptor = new McpAuthInterceptor();
        ReflectionTestUtils.setField(interceptor, "userTokenAuthenticator", authenticator);
        ReflectionTestUtils.setField(interceptor, "mcpServerMapper", mcpServerMapper);
    }

    @Test
    void initializeWithoutTokenReturns401() throws Exception {
        when(mcpServerMapper.selectOneByPath(PATH)).thenReturn(server());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request(PATH, "initialize", null), response, new Object()));
        assertEquals(401, response.getStatus());
        assertTrue(response.getHeader("WWW-Authenticate").contains("error=\"invalid_request\""));
        verifyNoInteractions(userTokenVerifier);
    }

    @Test
    void initializeWithInvalidTokenReturns401() throws Exception {
        when(mcpServerMapper.selectOneByPath(PATH)).thenReturn(server());
        when(userTokenVerifier.verify(USER_TOKEN)).thenReturn(null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request(PATH, "initialize", USER_TOKEN), response, new Object()));
        assertEquals(401, response.getStatus());
        assertTrue(response.getHeader("WWW-Authenticate").contains("error=\"invalid_token\""));
        assertTrue(response.getContentAsString().contains("-32001"));
    }

    @Test
    void verifierUnavailableReturns503() throws Exception {
        when(mcpServerMapper.selectOneByPath(PATH)).thenReturn(server());
        when(userTokenVerifier.verify(USER_TOKEN)).thenThrow(new UserTokenUnavailableException("down"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request(PATH, "initialize", USER_TOKEN), response, new Object()));
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("-32603"));
    }

    @Test
    void initializeWithValidTokenSetsIdentity() throws Exception {
        when(mcpServerMapper.selectOneByPath(PATH)).thenReturn(server());
        when(userTokenVerifier.verify(USER_TOKEN)).thenReturn(user());
        McpRequestWrapper request = request(PATH, "initialize", USER_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            assertTrue(interceptor.preHandle(request, response, new Object()));
            assertEquals("u-1", request.getAttribute(McpAuthInterceptor.MCP_USER_ID_ATTR));
            assertEquals("C-1", request.getAttribute(McpAuthInterceptor.MCP_CUSTOMER_ID_ATTR));
            assertNotNull(McpIdentityContext.get());
        } finally {
            McpIdentityContext.clear();
        }
    }

    @Test
    void toolsListWithoutTokenReturns401() throws Exception {
        when(mcpServerMapper.selectOneByPath(PATH)).thenReturn(server());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request(PATH, "tools/list", null), response, new Object()));
        assertEquals(401, response.getStatus());
    }

    @Test
    void toolsCallWithValidTokenIsAllowed() throws Exception {
        when(mcpServerMapper.selectOneByPath(PATH)).thenReturn(server());
        when(userTokenVerifier.verify(USER_TOKEN)).thenReturn(user());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request(PATH, "tools/call", USER_TOKEN), response, new Object()));
    }

    @Test
    void pingWithoutTokenIsAllowed() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request(PATH, "ping", null), response, new Object()));
        verifyNoInteractions(userTokenVerifier);
    }

    @Test
    void nonMcpPathIsIgnored() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request("/mcp/admin/servers", "tools/call", null), response, new Object()));
        verifyNoInteractions(userTokenVerifier);
    }

    private McpRequestWrapper request(String path, String method, String token) throws Exception {
        MockHttpServletRequest raw = new MockHttpServletRequest("POST", path);
        raw.setContentType("application/json");
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\"}";
        raw.setContent(body.getBytes(StandardCharsets.UTF_8));
        if (token != null) {
            raw.addHeader("x-dsh-auth-token", token);
        }
        return new McpRequestWrapper(raw);
    }

    private McpServer server() {
        McpServer server = new McpServer();
        server.setId(10);
        server.setPath(PATH);
        return server;
    }

    private UserIdentity user() {
        UserIdentity user = new UserIdentity();
        user.setUserId("u-1");
        user.setCustomerId("C-1");
        user.setTokenType("dsh");
        return user;
    }
}
