package com.mcp.transfer.gateway.route;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 数据面启动引导：应用启动后把 {@code mcp_server} 里启用的 path 注册成 MCP 端点。
 *
 * <p>原先由控制面的 {@code McpServerAdminController} 通过 {@code CommandLineRunner} 触发，
 * 属于「控制面初始化数据面」的反向依赖；拆到数据面后，控制面只负责写库与发布变更事件。
 */
@Component
public class McpRouteBootstrap implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(McpRouteBootstrap.class);

    @Autowired
    private McpServerDynamicControllerManager routeManager;

    @Override
    public void run(ApplicationArguments args) {
        try {
            routeManager.initOnStart();
            logger.info("[ROUTE] 启动注册完成");
        } catch (Exception e) {
            logger.error("[ROUTE] 启动注册失败，可通过 POST /mcp/admin/refresh-server-api 手动重试", e);
        }
    }
}
