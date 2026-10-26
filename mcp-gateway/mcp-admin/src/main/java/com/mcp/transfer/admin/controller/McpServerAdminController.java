package com.mcp.transfer.admin.controller;

import com.mcp.transfer.shared.dto.Result;
import com.mcp.transfer.port.ConfigChangePublisher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 资产缓存刷新入口（仅人工触发）。
 *
 * <p>旧的 {@code /mcp/v1} 动态路由已下线；本接口现在只发布「全部 Server 配置变更」事件，
 * 让数据面清空工具/资产缓存并通知已连接的客户端重新拉取。
 */
@Tag(name = "缓存刷新")
@RestController
@RequestMapping("/mcp/admin")
public class McpServerAdminController {

    @Autowired
    private ConfigChangePublisher configChangePublisher;

    @Operation(summary = "刷新数据面资产缓存")
    @PostMapping("/refresh-server-api")
    public Result<String> refreshServerApi() {
        configChangePublisher.serverChanged(null);
        return Result.success("已通知数据面刷新资产缓存");
    }
}
