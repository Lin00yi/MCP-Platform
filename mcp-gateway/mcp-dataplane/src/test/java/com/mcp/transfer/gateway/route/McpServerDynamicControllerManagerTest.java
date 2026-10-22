package com.mcp.transfer.gateway.route;

import com.mcp.transfer.constants.CommConstants;
import com.mcp.transfer.entity.McpServer;
import com.mcp.transfer.gateway.controller.McpController;
import com.mcp.transfer.mapper.McpServerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Arrays;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 动态路由刷新幂等性回归：每次 registerMapping 的 RequestMappingInfo 因 builder options 不同而不相等，
 * 若不先 unregister 旧映射，重复刷新会累积同一 path 的映射并触发
 * “Ambiguous handler methods mapped for '/mcp/v1/xxx'”。
 */
@ExtendWith(MockitoExtension.class)
class McpServerDynamicControllerManagerTest {

    @Mock
    private McpServerMapper mcpServerMapper;

    @Mock
    private McpController mcpController;

    @Mock
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @InjectMocks
    private McpServerDynamicControllerManager manager;

    @BeforeEach
    void setUp() {
        lenient().when(requestMappingHandlerMapping.getBuilderConfiguration())
                .thenReturn(new RequestMappingInfo.BuilderConfiguration());
        when(mcpServerMapper.getAllByStatus(CommConstants.TENANT_API_OPEN_STATUS))
                .thenReturn(Arrays.asList(server(1, "/mcp/v1/alpha"), server(2, "/mcp/v1/beta")));
    }

    private static McpServer server(int id, String path) {
        McpServer server = new McpServer();
        server.setId(id);
        server.setName("s" + id);
        server.setPath(path);
        server.setStatus(1);
        return server;
    }

    @Test
    void repeatedRefreshReplacesInsteadOfAccumulatingMappings() {
        manager.refreshAllServerController();
        // 每个 path 注册 POST/GET/DELETE 三条，共 2 * 3 = 6 条；首轮无需取消旧映射
        verify(requestMappingHandlerMapping, times(6)).registerMapping(any(), eq(mcpController), any());
        verify(requestMappingHandlerMapping, times(0)).unregisterMapping(any());

        manager.refreshAllServerController();
        // 第二轮必须先取消上一轮的 6 条旧映射，再重新注册 6 条，总量仍是 6 条
        verify(requestMappingHandlerMapping, times(6)).unregisterMapping(any());
        verify(requestMappingHandlerMapping, times(12)).registerMapping(any(), eq(mcpController), any());

        manager.refreshAllServerController();
        verify(requestMappingHandlerMapping, times(12)).unregisterMapping(any());
        verify(requestMappingHandlerMapping, times(18)).registerMapping(any(), eq(mcpController), any());
    }
}
