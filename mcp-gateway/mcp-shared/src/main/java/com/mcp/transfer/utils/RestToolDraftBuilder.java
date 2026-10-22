package com.mcp.transfer.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * REST 请求示例 → MCP Tool 草稿生成器（纯函数，无 Spring 依赖，便于单测）。
 *
 * <p>输入：URL（path / query 中用 <code>{参数}</code> 占位）、HTTP 方法、body 示例 JSON；
 * 输出：<code>serviceUrl / requestType / authType / resJson / paramMapping</code> 五项草稿字段。</p>
 *
 * <p>生成规则：</p>
 * <ul>
 *   <li><b>path</b>：<code>/{id}</code> → <code>paramMapping.path.id = {{input.id}}</code>；占位符保留在
 *       serviceUrl 中，由运行时 {@code applyPathMapping} 替换。</li>
 *   <li><b>query</b>：<code>k={x}</code> → <code>paramMapping.query.k = {{input.x}}</code>，并把该键值对
 *       <b>从 serviceUrl 中剥离</b>（运行时按映射追加），剩余静态 query 参数原样保留。
 *       旧实现保留占位符会导致真实调用 URL 里残留 <code>{x}</code>，{@code new URI} 抛
 *       Illegal character in query。</li>
 *   <li><b>body</b>：body 示例的顶层字段 → <code>paramMapping.body.k = {{input.k}}</code>；
 *       schema 按 JSON 示例<b>递归推导</b>（object / array / integer / number / boolean / string，
 *       null 不限类型）。</li>
 *   <li><b>required</b>：只有 path / query 占位符（用户显式标注的入参）进 required；
 *       body 示例字段不进（示例值不代表必填，避免过度约束导致调用被校验拒绝）。</li>
 * </ul>
 */
public final class RestToolDraftBuilder {

    /** path 中的 {xxx}（不允许嵌套花括号） */
    private static final Pattern PATH_PLACEHOLDER = Pattern.compile("\\{([^{}]+)\\}");
    /** query 值必须“整体”是一个占位符，如 k={x}；k=pre-{x} 视为静态 */
    private static final Pattern QUERY_PLACEHOLDER = Pattern.compile("^\\{([^{}]+)\\}$");
    /** body 示例递归推导的最大深度，防御异常深的嵌套把栈打爆 */
    private static final int MAX_SCHEMA_DEPTH = 10;

    private RestToolDraftBuilder() {
    }

    /**
     * 生成工具草稿。
     *
     * @param name        Tool 展示名（可空）
     * @param code        Tool 标识（可空；为空时 resJson.name 回退到 name）
     * @param description 描述（可空）
     * @param method      HTTP 方法（GET/POST…，可空，默认 GET）
     * @param url         REST 地址，path / query 用 {参数} 占位
     * @param bodyExample 请求体示例 JSON（可空）
     * @param authType    鉴权方式（可空，默认 NONE）
     * @return serviceUrl / requestType / authType / resJson / paramMapping
     */
    public static Map<String, Object> buildDraft(String name, String code, String description,
                                                 String method, String url, String bodyExample,
                                                 String authType) {
        if (!StringUtils.hasText(url)) {
            throw new IllegalArgumentException("url 不能为空");
        }

        JSONObject properties = new JSONObject();
        JSONObject queryMapping = new JSONObject();
        JSONObject pathMapping = new JSONObject();
        JSONObject bodyMapping = new JSONObject();
        Set<String> required = new LinkedHashSet<>();

        // 手动拆分 path / query（避免 new URI 对 {占位符} 报非法字符）
        int qIdx = url.indexOf('?');
        String pathPart = qIdx >= 0 ? url.substring(0, qIdx) : url;
        String queryPart = qIdx >= 0 ? url.substring(qIdx + 1) : "";

        // 1. path 占位符 → path 参数（serviceUrl 保留占位符，运行时替换）
        Matcher pm = PATH_PLACEHOLDER.matcher(pathPart);
        while (pm.find()) {
            String pname = pm.group(1);
            pathMapping.put(pname, "{{input." + pname + "}}");
            properties.put(pname, stringSchema());
            required.add(pname);
        }

        // 2. query 占位符 → query 参数，并从 serviceUrl 中剥离；静态 query 原样保留
        List<String> staticQueryPairs = new ArrayList<>();
        if (!queryPart.isEmpty()) {
            for (String pair : queryPart.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    // 无值参数（如 ?debug）原样保留
                    staticQueryPairs.add(pair);
                    continue;
                }
                String k = pair.substring(0, eq);
                String v = pair.substring(eq + 1);
                Matcher qm = QUERY_PLACEHOLDER.matcher(v);
                if (qm.matches()) {
                    String pname = qm.group(1);
                    queryMapping.put(k, "{{input." + pname + "}}");
                    properties.put(pname, stringSchema());
                    required.add(pname);
                } else {
                    staticQueryPairs.add(pair);
                }
            }
        }

        // 3. body 示例 → body 参数（递归推导 schema）
        if (StringUtils.hasText(bodyExample)) {
            try {
                Object parsed = JSON.parse(bodyExample);
                if (parsed instanceof JSONObject) {
                    JSONObject bo = (JSONObject) parsed;
                    for (Map.Entry<String, Object> e : bo.entrySet()) {
                        bodyMapping.put(e.getKey(), "{{input." + e.getKey() + "}}");
                        properties.put(e.getKey(), inferSchema(e.getValue(), 0));
                    }
                }
                // 非 object 的 body（数组/裸值）暂不支持，忽略
            } catch (Exception ignore) {
                // bodyExample 非法 JSON：忽略（不阻断生成，用户可在表单里手改 resJson）
            }
        }

        // 4. 组装 inputSchema（type=object + properties + required）
        JSONObject inputSchema = new JSONObject();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        if (!required.isEmpty()) {
            inputSchema.put("required", new ArrayList<>(required));
        }

        JSONObject resJson = new JSONObject();
        resJson.put("name", StringUtils.hasText(code) ? code : name);
        resJson.put("description", description == null ? "" : description);
        resJson.put("inputSchema", inputSchema);

        JSONObject paramMapping = new JSONObject();
        if (!queryMapping.isEmpty()) {
            paramMapping.put("query", queryMapping);
        }
        if (!pathMapping.isEmpty()) {
            paramMapping.put("path", pathMapping);
        }
        if (!bodyMapping.isEmpty()) {
            paramMapping.put("body", bodyMapping);
        }

        StringBuilder serviceUrl = new StringBuilder(pathPart);
        if (!staticQueryPairs.isEmpty()) {
            serviceUrl.append('?').append(String.join("&", staticQueryPairs));
        }

        Map<String, Object> result = new java.util.HashMap<>();
        result.put("serviceUrl", serviceUrl.toString());
        result.put("requestType", StringUtils.hasText(method) ? method.toUpperCase(Locale.ROOT) : "GET");
        result.put("authType", StringUtils.hasText(authType) ? authType : "NONE");
        result.put("resJson", resJson.toJSONString());
        result.put("paramMapping", paramMapping.toJSONString());
        return result;
    }

    private static JSONObject stringSchema() {
        JSONObject p = new JSONObject();
        p.put("type", "string");
        return p;
    }

    /**
     * 按 JSON 示例值递归推导 JSON Schema 片段。
     * null 值不限定类型（空 schema），空数组 items 也为空 schema（不限定元素类型）。
     */
    private static JSONObject inferSchema(Object value, int depth) {
        JSONObject schema = new JSONObject();
        if (value == null || depth > MAX_SCHEMA_DEPTH) {
            return schema;
        }
        if (value instanceof JSONObject) {
            JSONObject props = new JSONObject();
            for (Map.Entry<String, Object> e : ((JSONObject) value).entrySet()) {
                props.put(e.getKey(), inferSchema(e.getValue(), depth + 1));
            }
            schema.put("type", "object");
            schema.put("properties", props);
            return schema;
        }
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            schema.put("type", "array");
            schema.put("items", arr.isEmpty() ? new JSONObject() : inferSchema(arr.get(0), depth + 1));
            return schema;
        }
        schema.put("type", inferScalarType(value));
        return schema;
    }

    private static String inferScalarType(Object v) {
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte
                || v instanceof BigInteger) {
            return "integer";
        }
        if (v instanceof Float || v instanceof Double || v instanceof BigDecimal) {
            return "number";
        }
        if (v instanceof Boolean) {
            return "boolean";
        }
        return "string";
    }
}
