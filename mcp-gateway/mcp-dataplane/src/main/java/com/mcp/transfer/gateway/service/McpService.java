package com.mcp.transfer.gateway.service;

import com.alibaba.fastjson.JSONObject;
import com.mcp.transfer.dto.mcp.req.McpRequest;
import com.mcp.transfer.dto.mcp.res.McpResponse;
import com.mcp.transfer.entity.McpServer;
import com.mcp.transfer.entity.McpTool;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * @Title: McpService
 * @Package: com.mcp.transfer.service
 * @Description: MCP通用接口
 * @author: zhangXu
 * @date: 2026/1/12 11:24
 * @version: V1.0
 */
public interface McpService {

    /**
     * @Description: 核心方法处理
     * @author: zhangXu
     * @date: 2026/1/12 13:16
     */
    McpResponse handleMcpRequest(McpRequest mcpRequest, HttpServletRequest request);

    /**
     * 工具注册前连通性测试：本地工具校验类方法格式，HTTP 工具实际发起一次请求并返回结果。
     *
     * @param tool    工具定义（authConfig 明文或密文均可）
     * @param request 请求上下文（SK_EXCHANGE 需要 Authorization 头里的 sk）
     * @return {ok, status, duration, body|error|note}
     */
    Map<String, Object> testConnection(McpTool tool, String authorization);

    /**
     * 按请求路径解析 serverId（用于 SSE 会话与 server 关联、以及 list_changed 通知）。
     *
     * @param path 请求 URI，如 /mcp/v1/pcb-server
     * @return serverId，未找到返回 null
     */
    Integer resolveServerIdByPath(String path);

    // ==================== 统一入口（方案 C）复用入口 ====================

    /** 在指定 Server 上执行工具（校验参数、调用、写 tool_call_record）。 */
    Object executeToolOnServer(McpServer server, McpTool tool, McpRequest request, HttpServletRequest httpRequest);

    /** 在指定 Server 上读取 Prompt。 */
    JSONObject promptGetOnServer(McpServer server, String promptName, String appId);

    /** 在指定 Server 上读取 Resource。 */
    JSONObject resourceReadOnServer(McpServer server, String uri, String appId);

    /** 构建单个工具的 MCP 定义（含 schema 瘦身）。 */
    com.mcp.transfer.dto.mcp.result.tools.McpTool toToolDto(McpServer server, McpTool tool);

    /** 身份下传开启时，用当前客编覆盖客户端传入的客编字段。 */
    void overrideCustomerArgs(McpRequest request);

    /** 写入 mcp_request_log（统一入口与独立端点共用）。 */
    void saveMcpRequestLog(String method, String methodName, String requestPath,
                           HttpServletRequest request, McpRequest mcpRequest, McpResponse response);
}