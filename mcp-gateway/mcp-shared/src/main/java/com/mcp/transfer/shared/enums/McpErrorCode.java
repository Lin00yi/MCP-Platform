package com.mcp.transfer.shared.enums;

import lombok.Getter;

/**
 * MCP协议错误码枚举（JSON-RPC 2.0标准 + MCP扩展）
 */
@Getter
public enum McpErrorCode {
    /**
     * JSON解析错误
     */
    PARSE_ERROR(-32700, "Parse error", "JSON格式解析失败，请求体不是合法的JSON字符串"),
    /**
     * 无效的请求格式
     */
    INVALID_REQUEST(-32600, "Invalid Request", "请求格式不符合JSON-RPC 2.0规范（如缺少jsonrpc字段、id格式非法等）"),
    /**
     * 方法不存在
     */
    METHOD_NOT_FOUND(-32601, "Method not found", "请求的方法名不存在（如非initialize/mcp/initialize/tools/*等）"),
    /**
     * 参数无效
     */
    INVALID_PARAMS(-32602, "Invalid params", "请求参数类型/值/必填项不符合要求（如params不是JSON对象、缺少必填参数等）"),
    /**
     * 服务器内部错误
     */
    INTERNAL_ERROR(-32603, "Internal error", "服务器处理请求时发生未知异常（如数据库连接失败、第三方接口调用异常等）"),
    /**
     * 资源未找到（MCP扩展错误码）
     */
    RESOURCE_NOT_FOUND(-32002, "Resource not found", "请求的MCP资源不存在（如工具名、资源URI不存在等）"),
    /**
     * 未认证（access-token缺失或验签失败）
     */
    UNAUTHORIZED(-32001, "Unauthorized", "access-token缺失或验签失败，请携带有效的JWT凭证"),
    /**
     * 无权限（应用未授权调用该MCP方法）
     */
    FORBIDDEN(-32003, "Forbidden", "该应用无权限"),

    /**
     * 身份下传：用户 token 缺失/无效
     */
    IDENTITY_REQUIRED(-32001, "Unauthorized", "x-dsh-auth-token 缺失或用户身份无效"),

    /**
     * 身份下传：未取得当前客编
     */
    CUSTOMER_REQUIRED(-32003, "Forbidden", "未取得当前客编或客编不可用"),

    /**
     * 身份下传：用户信息服务不可用
     */
    IDENTITY_UNAVAILABLE(-32603, "Internal error", "用户信息服务暂不可用"),


    NO_SELECT_USER(-10001, "NO_SELECT_USER", "未查找到用户数据");

    /**
     * JSON-RPC 2.0标准错误码
     */
    private final Integer code;
    /**
     * 错误含义（英文，符合标准）
     */
    private final String message;
    /**
     * 错误说明（中文，便于调试）
     */
    private final String description;

    McpErrorCode(Integer code, String message, String description) {
        this.code = code;
        this.message = message;
        this.description = description;
    }
}