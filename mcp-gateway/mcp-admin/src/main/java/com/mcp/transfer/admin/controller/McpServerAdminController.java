package com.mcp.transfer.admin.controller;

import com.mcp.transfer.dto.Result;
import com.mcp.transfer.port.ConfigChangePublisher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 动态路由管理入口（仅人工触发）。
 *
 * <p>启动注册已下沉到数据面的 {@code McpRouteBootstrap}；本接口只发布「路由变更」事件，
 * 由数据面自行重注册。物理拆分后事件经内部接口 / 消息投递，本类不再持有数据面对象。
 */
@Tag(name = "动态路由")
@RestController
@RequestMapping("/mcp/admin")
public class McpServerAdminController {

    @Autowired
    private ConfigChangePublisher configChangePublisher;

    @Operation(summary = "刷新动态路由")
    @PostMapping("/refresh-server-api")
    public Result<String> refreshServerApi() {
        configChangePublisher.routesChanged();
        return Result.success("已发布路由刷新事件");
    }
}
