package com.mcp.transfer.discovery;

import com.mcp.transfer.catalog.McpDiscoveryService;

import com.mcp.transfer.config.filter.McpDiscoveryAuthInterceptor;
import com.mcp.transfer.config.session.McpSessionCache;
import com.mcp.transfer.config.session.SseEmitterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpDiscoveryControllerTest {

    @Mock
    private McpDiscoveryService discoveryService;
    @Mock
    private McpSessionCache mcpSessionCache;
    @Mock
    private SseEmitterRegistry sseEmitterRegistry;

    @InjectMocks
    private McpDiscoveryController controller;

    @Test
    void returns304WhenEtagMatches() throws Exception {
        when(discoveryService.listServers("app", null, false))
                .thenReturn(List.of(Map.of("serverKey", "pcb", "revision", "r1")));
        when(discoveryService.revisionOf(anyList())).thenReturn("global-rev");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp/discovery/servers");
        request.setAttribute(McpDiscoveryAuthInterceptor.DISCOVERY_APP_ID_ATTR, "app");
        request.addHeader("If-None-Match", "\"global-rev\"");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertNull(controller.listServers(request, response));
        assertEquals(304, response.getStatus());
        assertEquals("\"global-rev\"", response.getHeader("ETag"));
    }
}
