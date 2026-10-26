package com.mcp.transfer.catalog;

import com.mcp.transfer.shared.entity.McpServer;
import com.mcp.transfer.shared.entity.McpTool;
import com.mcp.transfer.shared.mapper.McpPromptMapper;
import com.mcp.transfer.shared.mapper.McpResourceMapper;
import com.mcp.transfer.shared.mapper.McpServerMapper;
import com.mcp.transfer.shared.mapper.McpToolMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpDiscoveryServiceTest {

    @Mock
    private McpServerMapper mcpServerMapper;
    @Mock
    private McpToolMapper mcpToolMapper;
    @Mock
    private McpPromptMapper mcpPromptMapper;
    @Mock
    private McpResourceMapper mcpResourceMapper;
    @Mock
    private com.mcp.transfer.health.McpServerHealthService healthService;

    @InjectMocks
    private McpDiscoveryService service;

    private com.mcp.transfer.exposure.ToolAccessPolicy policy;

    private com.mcp.transfer.cache.ToolListCache toolListCache;

    @BeforeEach
    void setUp() {
        // 真实策略对象 + mock 依赖：验证列表过滤与预算计算走同一套规则
        policy = new com.mcp.transfer.exposure.ToolAccessPolicy();
        toolListCache = new com.mcp.transfer.cache.ToolListCache();
        ReflectionTestUtils.setField(policy, "mcpToolMapper", mcpToolMapper);
        ReflectionTestUtils.setField(policy, "toolListCache", toolListCache);
        ReflectionTestUtils.setField(policy, "properties", new com.mcp.transfer.exposure.ToolExposureProperties());
        ReflectionTestUtils.setField(policy, "tokenEstimator", new com.mcp.transfer.exposure.TokenEstimator());
        ReflectionTestUtils.setField(service, "toolAccessPolicy", policy);
    }

    @Test
    void listServersAddsMetadataAndReturnsAllEnabledTools() {
        McpServer server = server();
        when(mcpServerMapper.getAllByStatus(1)).thenReturn(List.of(server));
        when(mcpToolMapper.selectAllByServerId(10)).thenReturn(List.of(tool(1, "add"), tool(2, "echo")));

        List<Map<String, Object>> servers = service.listServers("dsh", null, true);

        assertEquals(1, servers.size());
        Map<String, Object> item = servers.get(0);
        assertEquals("pcb", item.get("serverKey"));
        assertEquals("/mcp/v1/pcb-server", item.get("path"));
        assertEquals(2, item.get("toolCount"));
        assertEquals(64, ((String) item.get("revision")).length());
    }

    @Test
    void listServersReturnsAllEnabledServers() {
        when(mcpServerMapper.getAllByStatus(1)).thenReturn(List.of(server()));
        assertEquals(1, service.listServers("dsh", null, true).size());
    }

    @Test
    void revisionChangesWhenToolChanges() {
        McpServer server = server().setToolAccessMode(com.mcp.transfer.exposure.ToolExposureModes.ACCESS_ALL);
        when(mcpServerMapper.getAllByStatus(1)).thenReturn(List.of(server));

        McpTool first = tool(1, "add");
        when(mcpToolMapper.selectAllByServerId(10)).thenReturn(List.of(first));
        String before = (String) service.listServers("dsh", null, true).get(0).get("revision");

        McpTool changed = tool(1, "add");
        changed.setUpdateTime(new Date(System.currentTimeMillis() + 5000));
        when(mcpToolMapper.selectAllByServerId(10)).thenReturn(List.of(changed));
        // 线上由 ToolChangeNotifier 在工具变更时失效缓存，测试里手动模拟
        toolListCache.invalidate(10);
        String after = (String) service.listServers("dsh", null, true).get(0).get("revision");

        assertNotEquals(before, after);
    }

    private McpServer server() {
        return new McpServer()
                .setId(10)
                .setTenantId(5)
                .setName("pcb-server")
                .setServerKey("pcb")
                .setPath("/mcp/v1/pcb-server")
                .setStatus(1)
                .setUpstreamType("NONE")
                .setUpdateTime(new Date(1_700_000_000_000L));
    }

    private McpTool tool(int id, String code) {
        return new McpTool()
                .setId(id)
                .setServerId(10)
                .setCode(code)
                .setStatus(1)
                .setUpdateTime(new Date(1_700_000_000_000L));
    }
}
