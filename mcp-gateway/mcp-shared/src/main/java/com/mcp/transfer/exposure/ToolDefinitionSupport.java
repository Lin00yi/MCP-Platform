package com.mcp.transfer.exposure;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.mcp.transfer.entity.McpTool;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具定义（mcp_tool.res_json）的轻量读取工具。
 * 统一空值/脏数据处理，避免检索、瘦身、管理台各自实现一套解析。
 */
public final class ToolDefinitionSupport {

    private ToolDefinitionSupport() {
    }

    /** 解析 res_json；失败返回 null */
    public static JSONObject parseDefinition(McpTool tool) {
        if (tool == null || !StringUtils.hasText(tool.getResJson())) {
            return null;
        }
        try {
            return JSON.parseObject(tool.getResJson());
        } catch (Exception e) {
            return null;
        }
    }

    /** 工具描述；res_json 缺失时回落工具名称 */
    public static String description(McpTool tool) {
        JSONObject def = parseDefinition(tool);
        String description = def == null ? null : def.getString("description");
        if (StringUtils.hasText(description)) {
            return description;
        }
        return tool == null ? "" : (StringUtils.hasText(tool.getName()) ? tool.getName() : tool.getCode());
    }

    /** inputSchema；缺失返回 null */
    public static JSONObject inputSchema(McpTool tool) {
        JSONObject def = parseDefinition(tool);
        if (def == null) {
            return null;
        }
        JSONObject schema = def.getJSONObject("inputSchema");
        if (schema != null) {
            return schema;
        }
        // 兼容直接把 schema 存在 res_json 顶层的旧数据
        if (def.containsKey("properties") || def.containsKey("type")) {
            return def;
        }
        return null;
    }

    /** 逗号分隔的 tags -> List；空返回空列表 */
    public static List<String> tags(String tags) {
        List<String> list = new ArrayList<>();
        if (!StringUtils.hasText(tags)) {
            return list;
        }
        for (String tag : tags.split(",")) {
            if (StringUtils.hasText(tag)) {
                list.add(tag.trim());
            }
        }
        return list;
    }

    /** 截断字符串（不追加省略号时更省 token）；max<=0 表示不截断 */
    public static String truncate(String value, int max) {
        if (value == null || max <= 0 || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    /** 管理台/检索结果的短描述 */
    public static String shortDescription(McpTool tool, int max) {
        return truncate(description(tool), max);
    }
}
