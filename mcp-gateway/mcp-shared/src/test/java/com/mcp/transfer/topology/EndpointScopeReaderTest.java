package com.mcp.transfer.topology;

import com.mcp.transfer.exposure.ToolAccessPolicy;
import com.mcp.transfer.exposure.ToolExposureProperties;
import com.mcp.transfer.shared.entity.McpEndpoint;
import com.mcp.transfer.shared.entity.McpEndpointNamespace;
import com.mcp.transfer.shared.entity.McpNamespace;
import com.mcp.transfer.shared.entity.McpNamespaceServer;
import com.mcp.transfer.shared.entity.McpServer;
import com.mcp.transfer.shared.mapper.McpEndpointMapper;
import com.mcp.transfer.shared.mapper.McpEndpointNamespaceMapper;
import com.mcp.transfer.shared.mapper.McpNamespaceMapper;
import com.mcp.transfer.shared.mapper.McpNamespaceServerMapper;
import com.mcp.transfer.shared.mapper.McpServerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 拓扑解析缓存：同一 Endpoint 10s 内只解析一次；invalidateAll 后重新解析。
 */
@ExtendWith(MockitoExtension.class)
class EndpointScopeReaderTest {

    @Mock
    private McpEndpointMapper endpointMapper;
    @Mock
    private McpEndpointNamespaceMapper endpointNamespaceMapper;
    @Mock
    private McpNamespaceMapper namespaceMapper;
    @Mock
    private McpNamespaceServerMapper namespaceServerMapper;
    @Mock
    private McpServerMapper serverMapper;
    @Mock
    private ToolAccessPolicy toolAccessPolicy;
    @Mock
    private ToolExposureProperties toolExposureProperties;

    private EndpointScopeReader reader;

    @BeforeEach
    void setUp() {
        reader = new EndpointScopeReader();
        ReflectionTestUtils.setField(reader, "endpointMapper", endpointMapper);
        ReflectionTestUtils.setField(reader, "endpointNamespaceMapper", endpointNamespaceMapper);
        ReflectionTestUtils.setField(reader, "namespaceMapper", namespaceMapper);
        ReflectionTestUtils.setField(reader, "namespaceServerMapper", namespaceServerMapper);
        ReflectionTestUtils.setField(reader, "mcpServerMapper", serverMapper);
        ReflectionTestUtils.setField(reader, "toolAccessPolicy", toolAccessPolicy);
        ReflectionTestUtils.setField(reader, "toolExposureProperties", toolExposureProperties);
        ReflectionTestUtils.setField(reader, "publicBaseUrl", "http://gateway");
    }

    private McpEndpoint endpoint() {
        return new McpEndpoint().setId(1).setEndpointKey("team").setName("Team").setStatus(1);
    }

    private void stubTopology() {
        when(endpointMapper.selectByEndpointKey("team")).thenReturn(endpoint());
        when(endpointNamespaceMapper.selectByEndpointId(1)).thenReturn(List.of(
                new McpEndpointNamespace().setEndpointId(1).setNamespaceId(10)));
        when(namespaceMapper.selectByPrimaryKey(10)).thenReturn(
                new McpNamespace().setId(10).setPrefix("pcb").setStatus(1));
        when(namespaceServerMapper.selectByNamespaceId(10)).thenReturn(List.of(
                new McpNamespaceServer().setNamespaceId(10).setServerId(5)));
        when(serverMapper.selectByPrimaryKey(5)).thenReturn(new McpServer().setId(5).setStatus(1));
    }

    @Test
    void resolveScopeIsCachedAndInvalidated() {
        stubTopology();

        EndpointScope first = reader.resolveScope("team");
        EndpointScope second = reader.resolveScope("team");

        assertNotNull(first);
        assertSame(first, second, "10s 内应复用缓存实例");
        assertEquals("pcb", first.getPrefixByServer().get(5));
        verify(endpointMapper, times(1)).selectByEndpointKey("team");

        reader.invalidateAll();
        reader.resolveScope("team");
        verify(endpointMapper, times(2)).selectByEndpointKey("team");
    }

    @Test
    void unknownEndpointIsNegativelyCached() {
        when(endpointMapper.selectByEndpointKey("missing")).thenReturn(null);

        assertNull(reader.resolveScope("missing"));
        assertNull(reader.resolveScope("missing"));
        verify(endpointMapper, times(1)).selectByEndpointKey("missing");
    }

    @Test
    void emptyKeySkipsCacheAndDatabase() {
        assertNull(reader.resolveScope("  "));
        verify(endpointMapper, times(0)).selectByEndpointKey("  ");
    }

    @Test
    void discoveryServersIsCached() {
        stubTopology();
        when(endpointMapper.selectAll()).thenReturn(List.of(endpoint()));
        when(toolAccessPolicy.visibleTools(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(List.of());
        when(toolAccessPolicy.budget(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(new com.mcp.transfer.exposure.ToolBudget());

        List<java.util.Map<String, Object>> first = reader.discoveryServers();
        List<java.util.Map<String, Object>> second = reader.discoveryServers();

        assertEquals(1, first.size());
        assertSame(first, second, "5s 内应复用缓存列表");
        assertTrue(first.get(0).containsKey("serverKey"));
        verify(endpointMapper, times(1)).selectAll();
    }
}
