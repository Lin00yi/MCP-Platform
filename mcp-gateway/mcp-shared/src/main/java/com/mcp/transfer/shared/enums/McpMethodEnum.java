package com.mcp.transfer.shared.enums;

import lombok.Getter;

/**
 * @Title: McpMethodEnum
 * @Package: com.mcp.transfer.shared.enums
 * @Description: mcp固定方法
 * @author: zhangXu
 * @date: 2026/1/12 13:11
 * @version: V1.0
 */
@Getter
public enum McpMethodEnum {

    /**
     * 初始化请求
     */
    INITIALIZE("initialize", "MCP初始化请求，返回初始化能力结果"),

    /**
     * ping
     */
    PING("ping", "MCP初始化请求，返回初始化能力结果"),

    /**
     * 初始化完成通知
     */
    NOTIFICATIONS_INITIALIZED("notifications/initialized", "MCP初始化完成通知，无需返回业务结果"),

    /**
     * 工具列表查询
     */
    TOOLS_LIST("tools/list", "查询MCP支持的工具列表"),

    /**
     * 资源列表查询
     */
    RESOURCES_LIST("resources/list", "查询MCP支持的资源列表"),

    /**
     * 提示列表查询
     */
    PROMPTS_LIST("prompts/list", "查询MCP支持的提示列表"),

    /**
     * 资源模版查询
     */
    RESOURCES_TEMPLATES_LIST("resources/templates/list", "查询MCP支持的资源模版列表"),

    /**
     * 工具调用
     */
    TOOLS_CALL("tools/call", "调用MCP指定工具，执行具体业务逻辑"),

    /**
     * 获取 Prompt 内容
     */
    PROMPTS_GET("prompts/get", "获取指定 Prompt 的渲染结果"),

    /**
     * 读取 Resource 内容
     */
    RESOURCES_READ("resources/read", "读取指定 URI 的资源内容");


    /**
     * MCP请求的method值（前端传入的字符串）
     */
    private final String method;

    /**
     * 方法注释/说明
     */
    private final String comment;

    /**
     * 构造方法
     * @param method method值
     * @param comment 注释说明
     */
    McpMethodEnum(String method, String comment) {
        this.method = method;
        this.comment = comment;
    }

    /**
     * 根据method值获取枚举实例（核心工具方法）
     * @param method 前端传入的method字符串
     * @return 对应的枚举实例，无匹配则返回null
     */
    public static McpMethodEnum getByMethod(String method) {
        if (method == null || method.isEmpty()) {
            return null;
        }
        for (McpMethodEnum enumItem : McpMethodEnum.values()) {
            if (enumItem.getMethod().equals(method)) {
                return enumItem;
            }
            // 兼容tools/call开头的场景（如tools/call/xxx）
            if (enumItem == TOOLS_CALL && method.startsWith(enumItem.getMethod())) {
                return TOOLS_CALL;
            }
        }
        return null;
    }

}