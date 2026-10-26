package com.mcp.transfer.cluster;

import com.mcp.transfer.cache.PromptListCache;
import com.mcp.transfer.cache.ResourceListCache;
import com.mcp.transfer.cache.ToolListCache;
import com.mcp.transfer.config.session.SseEmitterRegistry;
import com.mcp.transfer.shared.entity.McpTool;
import com.mcp.transfer.exposure.ToolEmbeddingService;
import com.mcp.transfer.gateway.aggregate.McpAggregateService;
import com.mcp.transfer.shared.mapper.McpToolMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfigChangeApplierTest {

    @Mock
    private ClusterEventBus eventBus;
    @Mock
    private ToolListCache toolListCache;
    @Mock
    private PromptListCache promptListCache;
    @Mock
    private ResourceListCache resourceListCache;
    @Mock
    private SseEmitterRegistry sseEmitterRegistry;
    @Mock
    private McpAggregateService aggregateService;
    @Mock
    private ToolEmbeddingService toolEmbeddingService;
    @Mock
    private McpToolMapper mcpToolMapper;
    @Mock
    private com.mcp.transfer.topology.EndpointScopeReader endpointScopeReader;

    @InjectMocks
    private ConfigChangeApplier applier;

    private Consumer<ClusterEvent> handler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        doAnswer(invocation -> {
            handler = invocation.getArgument(0);
            return null;
        }).when(eventBus).subscribe(any());
        applier.subscribe();
    }

    @Test
    void toolEventInvalidatesCacheAndBroadcasts() {
        handler.accept(ClusterEvent.of(ClusterEvent.Type.TOOL, 5));

        verify(toolListCache).invalidate(5);
        verify(aggregateService).invalidateProxyCache(5);
        verify(endpointScopeReader).invalidateAll();
        verify(sseEmitterRegistry).broadcastToServer(eq(5), contains("notifications/tools/list_changed"));
        verify(sseEmitterRegistry).broadcastToAggregate(contains("notifications/tools/list_changed"));
    }

    @Test
    void promptAndResourceEventsAreSeparated() {
        handler.accept(ClusterEvent.of(ClusterEvent.Type.PROMPT, 7));
        handler.accept(ClusterEvent.of(ClusterEvent.Type.RESOURCE, 8));

        verify(promptListCache).invalidate(7);
        verify(resourceListCache).invalidate(8);
        verify(sseEmitterRegistry).broadcastToServer(eq(7), contains("prompts/list_changed"));
        verify(sseEmitterRegistry).broadcastToServer(eq(8), contains("resources/list_changed"));
    }

    @Test
    void toolUpsertIndexesOnlyThatTool() {
        McpTool tool = new McpTool().setId(99).setServerId(5);
        when(mcpToolMapper.selectByPrimaryKey(99)).thenReturn(tool);

        handler.accept(ClusterEvent.tool(5, 99, "UPSERT"));

        verify(toolEmbeddingService).indexToolAsync(tool);
    }

    @Test
    void toolDeleteRemovesVectorOnly() {
        handler.accept(ClusterEvent.tool(5, 99, "DELETE"));

        verify(toolEmbeddingService).deleteTool(99, 5);
        verify(toolEmbeddingService, never()).indexToolAsync(any());
    }

    @Test
    void serverPurgeDropsAllVectors() {
        handler.accept(ClusterEvent.tool(5, null, "PURGE_SERVER"));

        verify(toolEmbeddingService).deleteServer(5);
    }

    @Test
    void toolEventWithoutToolIdRebuildsWholeServer() {
        java.util.List<McpTool> tools = java.util.Collections.singletonList(new McpTool().setId(1));
        when(mcpToolMapper.selectAllByServerId(5)).thenReturn(tools);

        handler.accept(ClusterEvent.of(ClusterEvent.Type.TOOL, 5));

        verify(toolEmbeddingService).reindexServerAsync(5, tools);
    }

    @Test
    void serverEventInvalidatesAllAssetCachesAndBroadcasts() {
        handler.accept(ClusterEvent.of(ClusterEvent.Type.SERVER, 5));

        verify(toolListCache).invalidate(5);
        verify(promptListCache).invalidate(5);
        verify(resourceListCache).invalidate(5);
        verify(aggregateService).invalidateProxyCache(5);
        verify(sseEmitterRegistry).broadcastToServer(eq(5), contains("tools/list_changed"));
        verify(sseEmitterRegistry).broadcastToServer(eq(5), contains("prompts/list_changed"));
        verify(sseEmitterRegistry).broadcastToServer(eq(5), contains("resources/list_changed"));
    }

    @Test
    void allEventClearsEveryCacheAndBroadcasts() {
        handler.accept(ClusterEvent.of(ClusterEvent.Type.ALL, null));

        verify(toolListCache).clear();
        verify(promptListCache).clear();
        verify(resourceListCache).clear();
        verify(aggregateService).invalidateProxyCache(null);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(sseEmitterRegistry, org.mockito.Mockito.atLeastOnce())
                .broadcastToAggregate(message.capture());
        assertTrue(message.getAllValues().stream().anyMatch(m -> m.contains("tools/list_changed")));
    }
}
