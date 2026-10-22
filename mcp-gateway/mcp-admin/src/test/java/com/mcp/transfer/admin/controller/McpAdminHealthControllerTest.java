package com.mcp.transfer.admin.controller;

import com.mcp.transfer.dto.Result;
import com.mcp.transfer.entity.McpServer;
import com.mcp.transfer.mapper.McpServerMapper;
import com.mcp.transfer.port.HealthQueryPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 控制面健康接口：数据面可达时透传，不可达时返回 503；无权限的 Server 直接 404。
 */
@ExtendWith(MockitoExtension.class)
class McpAdminHealthControllerTest {

    @Mock
    private McpServerMapper mcpServerMapper;
    @Mock
    private HealthQueryPort healthQueryPort;

    @InjectMocks
    private McpAdminHealthController controller;

    @AfterEach
    void clearContext() {
        com.mcp.transfer.admin.security.AdminAuthContext.clear();
    }

    @Test
    void listAllPassesThroughDataPlaneResult() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("serverId", 1);
        health.put("status", "UP");
        when(healthQueryPort.listAll()).thenReturn(Collections.singletonList(health));

        Result<List<Map<String, Object>>> result = controller.listAll();

        assertEquals(0, result.getCode());
        assertEquals(1, result.getData().size());
    }

    @Test
    void dataPlaneUnavailableMapsTo503() {
        when(healthQueryPort.listAll())
                .thenThrow(new HealthQueryPort.UnavailableException("数据面不可达"));

        Result<List<Map<String, Object>>> result = controller.listAll();

        assertEquals(503, result.getCode());
    }

    @Test
    void unknownServerReturns404WithoutCallingDataPlane() {
        when(mcpServerMapper.selectByPrimaryKey(9)).thenReturn(null);

        Result<Map<String, Object>> result = controller.one(9);

        assertEquals(404, result.getCode());
        verify(healthQueryPort, never()).one(9);
    }

    @Test
    void probeDelegatesToDataPlane() {
        McpServer server = new McpServer().setId(5).setTenantId(1).setStatus(1);
        when(mcpServerMapper.selectByPrimaryKey(5)).thenReturn(server);
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        when(healthQueryPort.probe(5)).thenReturn(health);

        Result<Map<String, Object>> result = controller.probe(5);

        assertEquals(0, result.getCode());
        assertEquals("UP", result.getData().get("status"));
    }
}
