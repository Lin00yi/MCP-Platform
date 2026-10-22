package com.mcp.transfer.gateway.route;

import com.mcp.transfer.config.security.McpPathValidator;
import com.mcp.transfer.constants.CommConstants;
import com.mcp.transfer.dto.mcp.req.McpRequest;
import com.mcp.transfer.entity.McpServer;
import com.mcp.transfer.gateway.controller.McpController;
import com.mcp.transfer.mapper.McpServerMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于 mcp_server 表的动态 MCP 端点注册器。
 *
 * <p>与旧实现的区别：
 * <ul>
 *   <li>所有路径复用同一个 {@link McpController} 单例，不再为每个 path 创建 Bean；</li>
 *   <li><b>先注册新映射、再清理失效映射</b>，避免“先删后建”造成的路由真空；</li>
 *   <li>注册前用 {@link McpPathValidator} 校验，非法/保留路径直接跳过并告警；</li>
 *   <li>整个刷新过程加锁，防止并发刷新互相覆盖。</li>
 * </ul>
 */
@Component
public class McpServerDynamicControllerManager {

    private static final Logger logger = LoggerFactory.getLogger(McpServerDynamicControllerManager.class);

    /** 已注册映射（key = 方法|路径）→ 注册信息，用于幂等覆盖与清理失效映射 */
    private final Map<String, RegisteredMapping> registeredMappings = new LinkedHashMap<>();

    /** 一条已注册的映射记录 */
    private static final class RegisteredMapping {
        private final String path;
        private final RequestMappingInfo info;

        private RegisteredMapping(String path, RequestMappingInfo info) {
            this.path = path;
            this.info = info;
        }
    }

    private final Object refreshLock = new Object();

    @Resource
    private McpServerMapper mcpServerMapper;

    @Autowired
    private McpController mcpController;

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    /**
     * 项目启动时自动注册所有启用的 MCP Server 端点。
     */
    public void initOnStart() {
        String result = refreshAllServerController();
        logger.info("MCP Server 动态路由启动注册完成：{}", result);
    }

    /**
     * 全量刷新：先注册/覆盖新映射，再清理已失效的旧映射。
     */
    public String refreshAllServerController() {
        synchronized (refreshLock) {
            try {
                List<McpServer> serverList = mcpServerMapper.getAllByStatus(CommConstants.TENANT_API_OPEN_STATUS);
                if (serverList == null || serverList.isEmpty()) {
                    removeStaleMappings(new LinkedHashSet<>());
                    return "刷新完成：mcp_server 表无启用的端点配置";
                }

                Set<String> desiredPaths = new LinkedHashSet<>();
                int invalid = 0;
                for (McpServer server : serverList) {
                    try {
                        String path = McpPathValidator.validateAndNormalize(server.getPath());
                        desiredPaths.add(path);
                    } catch (Exception e) {
                        invalid++;
                        logger.error("[ROUTE] 跳过非法路径 serverId={}, name={}, path={}, error={}",
                                server.getId(), server.getName(), server.getPath(), e.getMessage());
                    }
                }

                int successCount = 0;
                for (String path : desiredPaths) {
                    try {
                        registerPath(path);
                        successCount++;
                    } catch (Exception e) {
                        logger.error("[ROUTE] 注册路径失败: {}", path, e);
                    }
                }

                removeStaleMappings(desiredPaths);
                return String.format("刷新成功：读取%s条启用配置，有效路径%s个，成功注册%s个，跳过非法%s个",
                        serverList.size(), desiredPaths.size(), successCount, invalid);
            } catch (Exception e) {
                logger.error("MCP Server 动态路由刷新失败", e);
                return "刷新失败：" + e.getMessage();
            }
        }
    }

    private void registerPath(String path) throws Exception {
        Method postMethod = McpController.class.getMethod(
                "handleMcpRequest", McpRequest.class, HttpServletRequest.class, HttpServletResponse.class);
        Method sseMethod = McpController.class.getMethod(
                "handleSseConnection", HttpServletRequest.class, HttpServletResponse.class);
        Method deleteMethod = McpController.class.getMethod(
                "handleSessionTermination", HttpServletRequest.class, HttpServletResponse.class);

        RequestMappingInfo.BuilderConfiguration options = requestMappingHandlerMapping.getBuilderConfiguration();

        RequestMappingInfo postInfo = RequestMappingInfo.paths(path).methods(RequestMethod.POST)
                .options(options).build();
        RequestMappingInfo getInfo = RequestMappingInfo.paths(path).methods(RequestMethod.GET)
                .produces(MediaType.TEXT_EVENT_STREAM_VALUE).options(options).build();
        RequestMappingInfo deleteInfo = RequestMappingInfo.paths(path).methods(RequestMethod.DELETE)
                .options(options).build();

        // 注意：registerMapping 只对“equals 相同”的 RequestMappingInfo 覆盖；而 RequestMappingInfo
        // 的相等性包含 builder options 等组件，每次 getBuilderConfiguration() 拿到的实例并不相等，
        // 直接 register 会不断累积同一 path 的重复映射（表现为 Ambiguous handler methods）。
        // 因此这里按「方法|路径」显式先 unregister 再 register，保证刷新幂等。
        registerMapping("POST|" + path, postInfo, postMethod);
        registerMapping("GET|" + path, getInfo, sseMethod);
        registerMapping("DELETE|" + path, deleteInfo, deleteMethod);
    }

    private void registerMapping(String key, RequestMappingInfo info, Method method) {
        RegisteredMapping previous = registeredMappings.get(key);
        if (previous != null) {
            try {
                requestMappingHandlerMapping.unregisterMapping(previous.info);
            } catch (Exception e) {
                logger.warn("[ROUTE] 取消旧映射失败: {}", key, e);
            }
        }
        requestMappingHandlerMapping.registerMapping(info, mcpController, method);
        registeredMappings.put(key, new RegisteredMapping(key.substring(key.indexOf('|') + 1), info));
    }

    private void removeStaleMappings(Set<String> desiredPaths) {
        List<String> staleKeys = new ArrayList<>();
        for (Map.Entry<String, RegisteredMapping> entry : registeredMappings.entrySet()) {
            if (!desiredPaths.contains(entry.getValue().path)) {
                staleKeys.add(entry.getKey());
            }
        }
        for (String key : staleKeys) {
            RegisteredMapping stale = registeredMappings.remove(key);
            if (stale == null) {
                continue;
            }
            try {
                requestMappingHandlerMapping.unregisterMapping(stale.info);
                logger.info("[ROUTE] 移除失效路由: {}", key);
            } catch (Exception e) {
                logger.warn("[ROUTE] 移除失效路由失败: {}", key, e);
            }
        }
    }
}
