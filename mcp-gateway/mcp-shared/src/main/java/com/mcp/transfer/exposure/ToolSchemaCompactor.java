package com.mcp.transfer.exposure;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * JSON Schema 双版本：
 * <ul>
 *   <li><b>full</b>：入库的完整 Schema，用于网关内部参数校验与排障；</li>
 *   <li><b>compact</b>：只保留模型填参所需的关键字，剔除 description / title /
 *       default / examples 等冗余字段，供 get_tool_schema 默认返回。</li>
 * </ul>
 *
 * <p>compact 保留 type / properties / required / enum / items / oneOf / anyOf / allOf /
 * additionalProperties / 数值与长度约束 / pattern 等校验关键字，保证模型仍能正确填参。
 */
public final class ToolSchemaCompactor {

    /** 直接保留的校验关键字 */
    private static final Set<String> KEEP_KEYS = new HashSet<>(Arrays.asList(
            "type", "properties", "required", "enum", "items", "oneOf", "anyOf", "allOf",
            "additionalProperties", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
            "minLength", "maxLength", "pattern", "minItems", "maxItems", "uniqueItems",
            "multipleOf", "const", "format", "$ref"));

    /** 明确剔除的展示/示例字段 */
    private static final Set<String> DROP_KEYS = new HashSet<>(Arrays.asList(
            "description", "title", "default", "examples", "example", "$schema", "$id",
            "$comment", "deprecated", "readOnly", "writeOnly"));

    private ToolSchemaCompactor() {
    }

    /** 生成精简版 Schema；输入为空时返回空对象。 */
    public static JSONObject compact(JSONObject schema) {
        if (schema == null) {
            return new JSONObject();
        }
        JSONObject result = new JSONObject();
        for (Map.Entry<String, Object> entry : schema.entrySet()) {
            String key = entry.getKey();
            if (DROP_KEYS.contains(key) || !KEEP_KEYS.contains(key)) {
                continue;
            }
            Object value = entry.getValue();
            if ("properties".equals(key) && value instanceof JSONObject) {
                JSONObject properties = new JSONObject();
                for (Map.Entry<String, Object> property : ((JSONObject) value).entrySet()) {
                    Object propertySchema = property.getValue();
                    if (propertySchema instanceof JSONObject) {
                        properties.put(property.getKey(), compact((JSONObject) propertySchema));
                    } else {
                        properties.put(property.getKey(), propertySchema);
                    }
                }
                result.put(key, properties);
            } else if ("items".equals(key) && value instanceof JSONObject) {
                result.put(key, compact((JSONObject) value));
            } else if (("oneOf".equals(key) || "anyOf".equals(key) || "allOf".equals(key))
                    && value instanceof JSONArray) {
                JSONArray array = new JSONArray();
                for (Object item : (JSONArray) value) {
                    array.add(item instanceof JSONObject ? compact((JSONObject) item) : item);
                }
                result.put(key, array);
            } else {
                result.put(key, value);
            }
        }
        return result;
    }
}
