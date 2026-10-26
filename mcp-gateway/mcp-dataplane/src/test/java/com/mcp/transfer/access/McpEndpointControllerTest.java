package com.mcp.transfer.access;

import com.mcp.transfer.config.filter.UserTokenAuthenticator;
import com.mcp.transfer.config.session.McpSessionCache;
import com.mcp.transfer.config.session.SseEmitterRegistry;
import com.mcp.transfer.shared.dto.mcp.req.McpRequest;
import com.mcp.transfer.shared.dto.mcp.res.McpResponse;
import com.mcp.transfer.shared.entity.McpEndpoint;
import com.mcp.transfer.gateway.McpUpstreamClient;
import com.mcp.transfer.gateway.aggregate.McpAggregateService;
import com.mcp.transfer.identity.ResolvedIdentity;
import com.mcp.transfer.topology.EndpointScope;
import com.mcp.transfer.topology.EndpointScopeReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpEndpointControllerTest {

    @Mock
    private EndpointScopeReader topologyService;
    @Mock
    private McpAggregateService aggregateService;
    @Mock
    private UserTokenAuthenticator userTokenAuthenticator;
    @Mock
    private McpSessionCache mcpSessionCache;
    @Mock
    private SseEmitterRegistry sseEmitterRegistry;
    @Mock
    private McpUpstreamClient mcpUpstreamClient;

    @InjectMocks
    private McpEndpointController controller;

    private EndpointScope scope;

    @BeforeEach
    void setUp() {
        McpEndpoint endpoint = new McpEndpoint()
                .setId(1)
                .setEndpointKey("team-pcb")
                .setName("Team PCB")
                .setStatus(1);
        scope = new EndpointScope(endpoint, Collections.singletonMap(10, "pcb"));
    }

    @Test
    void missingOrInvalidTokenStopsBeforeAggregate() throws Exception {
        McpRequest request = request("initialize");
        MockHttpServletRequest http = new MockHttpServletRequest("POST", "/mcp/e/team-pcb");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(userTokenAuthenticator.authenticate(http, response)).thenReturn(null);

        McpResponse result = controller.handle("team-pcb", request, http, response);

        assertNull(result);
        verify(aggregateService, never()).handle(any(), any());
        verify(mcpSessionCache, never()).generateSessionId();
    }

    @Test
    void validTokenReachesAggregate() throws Exception {
        McpRequest request = request("initialize");
        MockHttpServletRequest http = new MockHttpServletRequest("POST", "/mcp/e/team-pcb");
        MockHttpServletResponse response = new MockHttpServletResponse();
        McpResponse expected = new McpResponse();
        ResolvedIdentity identity = new ResolvedIdentity();
        identity.setUserId("u-1");

        when(userTokenAuthenticator.authenticate(http, response)).thenReturn(identity);
        when(topologyService.resolveScope("team-pcb")).thenReturn(scope);
        when(mcpSessionCache.generateSessionId()).thenReturn("sid-1");
        when(aggregateService.handle(eq(request), eq(http))).thenReturn(expected);

        McpResponse result = controller.handle("team-pcb", request, http, response);

        assertSame(expected, result);
        verify(aggregateService).handle(request, http);
        verify(mcpSessionCache).generateSessionId();
    }

    @Test
    void disabledEndpointReturns403() throws Exception {
        McpRequest request = request("initialize");
        MockHttpServletRequest http = new MockHttpServletRequest("POST", "/mcp/e/team-pcb");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ResolvedIdentity identity = new ResolvedIdentity();
        identity.setUserId("u-1");

        when(userTokenAuthenticator.authenticate(http, response)).thenReturn(identity);
        when(topologyService.resolveScope("team-pcb")).thenReturn(null);

        McpResponse result = controller.handle("team-pcb", request, http, response);

        assertEquals(403, response.getStatus());
        assertEquals(-32001, ((Number) ((java.util.Map<?, ?>) result.getError()).get("code")).intValue());
        verify(aggregateService, never()).handle(any(), any());
    }

    private McpRequest request(String method) {
        McpRequest request = new McpRequest();
        request.setJsonrpc("2.0");
        request.setId(1);
        request.setMethod(method);
        return request;
    }
}
