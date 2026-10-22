package com.mcp.transfer.dto.mcp.result.tools;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.annotation.JSONField;
import lombok.Data;

/**
 * 单个工具定义
 */

/**
 * 示列
 *              "name": "get-station-code-of-citys",
 *                 "description": "通过中文城市名查询代表该城市的 `station_code`。此接口主要用于在用户提供**城市名**作为出发地或到达地时，为接口准备 `station_code` 参数。",
 *                 "inputSchema": {
 *                     "type": "object",
 *                     "properties": {
 *                         "citys": {
 *                             "type": "string",
 *                             "description": "要查询的城市，比如\"北京\"。若要查询多个城市，请用|分割，比如\"北京|上海\"。"
 *                         }
 *                     },
 *                     "required": [
 *                         "citys"
 *                     ],
 *                     "additionalProperties": false,
 *                     "$schema": "http://json-schema.org/draft-07/schema#"
 *                 }
 */
@Data
public class McpTool {
    /**
     * 工具名称（如get-current-date、get-tickets）
     */
    @JSONField(name = "name")
    private String name;

    /**
     * 工具描述
     */
    @JSONField(name = "description")
    private String description;

    /**
     * 工具输入参数Schema（核心：定义请求字段）
     * 原样透传 JSON Schema（Draft-07），避免因固定 DTO 丢失 items、嵌套 properties/required 等关键字
     */
    @JSONField(name = "inputSchema")
    private JSONObject inputSchema;

    /**
     * 标签（资产目录检索用，扩展元数据）
     */
    @JSONField(name = "tags")
    private java.util.List<String> tags;

    /**
     * 版本号（扩展元数据，用于客户端版本感知）
     */
    @JSONField(name = "version")
    private String version;

    /**
     * 工具归属 server（扩展元数据，聚合场景区分来源）
     */
    @JSONField(name = "server")
    private JSONObject server;
}