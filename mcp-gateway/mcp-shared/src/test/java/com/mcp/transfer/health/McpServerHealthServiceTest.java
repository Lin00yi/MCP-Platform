package com.mcp.transfer.health;

import com.mcp.transfer.entity.McpServer;
import com.mcp.transfer.mapper.ToolCallRecordMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpServerHealthServiceTest {

    @Mock
    private ProbeResultCache probeCache;
    @Mock
    private McpServerHealthProbe probe;
    @Mock
    private ToolCallRecordMapper toolCallRecordMapper;

    @InjectMocks
    private McpServerHealthService service;

    private McpServer server() {
        return new McpServer().setId(1).setName("pcb").setStatus(1).setUpstreamType("NONE");
    }

    @Test
    void usesCachedProbeInsteadOfCallingUpstream() {
        when(probeCache.get(1)).thenReturn(new ProbeSnapshot(ProbeResult.ok(8, "cached"), System.currentTimeMillis()));
        when(toolCallRecordMapper.selectServerStatsSince(any())).thenReturn(Collections.emptyList());

        Map<String, Object> health = service.healthOf(server());

        assertEquals("UP", health.get("status"));
        assertEquals(8L, health.get("latencyMs"));
        verify(probe, never()).probe(any());
    }

    @Test
    void probesOnCacheMissAndStoresResult() {
        when(probeCache.get(1)).thenReturn(null);
        when(probe.probe(any())).thenReturn(ProbeResult.ok(15, "probed"));
        when(toolCallRecordMapper.selectServerStatsSince(any())).thenReturn(Collections.emptyList());

        Map<String, Object> health = service.healthOf(server());

        assertEquals("UP", health.get("status"));
        verify(probe).probe(any());
        verify(probeCache).put(any(Integer.class), any(ProbeSnapshot.class));
    }

    @Test
    void failedProbeMarksDown() {
        when(probeCache.get(1)).thenReturn(new ProbeSnapshot(ProbeResult.fail(30, "connect timeout"), System.currentTimeMillis()));
        when(toolCallRecordMapper.selectServerStatsSince(any())).thenReturn(Collections.emptyList());

        Map<String, Object> health = service.healthOf(server());

        assertEquals("DOWN", health.get("status"));
        assertEquals(false, health.get("ok"));
    }

    @Test
    void probeNowForcesProbeEvenWithCache() {
        // force=true 时直接探，不读缓存
        when(probe.probe(any())).thenReturn(ProbeResult.ok(22, "fresh"));
        when(toolCallRecordMapper.selectServerStatsSince(any())).thenReturn(Collections.emptyList());

        Map<String, Object> health = service.probeNow(server());

        assertEquals(22L, health.get("latencyMs"));
        verify(probe).probe(any());
    }

    @Test
    void disabledServerIsReportedWithoutProbing() {
        McpServer disabled = server().setStatus(0);

        Map<String, Object> health = service.healthOf(disabled);

        assertEquals("DISABLED", health.get("status"));
        assertEquals(false, health.get("ok"));
        verify(probe, never()).probe(any());
    }
}
