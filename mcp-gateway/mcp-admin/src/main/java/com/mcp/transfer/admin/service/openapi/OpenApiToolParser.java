package com.mcp.transfer.admin.service.openapi;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.Feature;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenAPI 3.x → MCP Tool 草稿解析器（纯函数，无 Spring 依赖，便于单测）。
 *
 * <p>一次调用解析整份文档，产出「每个 operation 一条草稿 + 文档级问题」的 {@link OpenApiImportPlan}。
 * 解析过程同时完成前置校验：只产出计划，不落库，由控制面决定导入哪些。</p>
 *
 * <p>校验规则（ERROR 阻塞导入 / WARN 提示）：</p>
 * <ul>
 *   <li>文档：必须是 OpenAPI 3.x（Swagger 2.0 明确拒绝）、paths 非空、servers[0].url 或入参 baseUrl
 *       必须是 <b>http(s) 绝对地址</b>（相对地址、变量无默认值都报错）。</li>
 *   <li>方法：网关运行时只实现 GET/POST，PUT/PATCH/DELETE/HEAD/OPTIONS 标记 ERROR。</li>
 *   <li>标识：operationId → code，非法字符替换、超长截断、同文档重复自动加后缀；
 *       与 Server 下已有 code 冲突标记 CONFLICT。</li>
 *   <li>参数：path/query/header 参数生成 paramMapping；path 参数强制必填；路径占位符未声明参数时补 string；
 *       cookie 参数不支持；query 数组/对象序列化给出警告。</li>
 *   <li>请求体：只支持 application/json；顶层 properties 展开为入参，required 合并进顶层；
 *       自由格式（无 properties）仅在无其他映射时允许透传；multipart/form-data 明确报错。</li>
 *   <li>$ref：仅解析本地引用（#/components/...），外部引用报错；allOf 合并；oneOf/anyOf 原样保留。</li>
 *   <li>鉴权：apiKey(header) → API_KEY、http bearer → BEARER（凭证仍需手工填），其余类型给出警告。</li>
 * </ul>
 */
public final class OpenApiToolParser {

    private static final Pattern PATH_VAR = Pattern.compile("\\{([^{}]+)\\}");
    private static final Set<String> HTTP_METHODS = new LinkedHashSet<>(
            Arrays.asList("get", "post", "put", "patch", "delete", "head", "options", "trace"));
    private static final Set<String> SUPPORTED_METHODS = new HashSet<>(Arrays.asList("get", "post"));
    private static final Set<String> SCHEMA_KEYS = new LinkedHashSet<>(Arrays.asList(
            "description", "title", "format", "enum", "default", "example", "const",
            "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
            "minLength", "maxLength", "pattern", "minItems", "maxItems", "uniqueItems"));

    private static final int MAX_CODE = 64;
    private static final int MAX_NAME = 255;
    private static final int MAX_TAGS = 255;
    private static final int MAX_VERSION = 32;
    private static final int MAX_URL = 500;
    private static final int MAX_DEPTH = 20;

    private OpenApiImportPlan plan;
    private JSONObject root;
    private JSONObject components = new JSONObject();

    private OpenApiToolParser() {
    }

    /**
     * @param document       OpenAPI JSON 原文
     * @param baseUrlOverride 可选：覆盖 servers[0].url
     * @param existingCodes 该 Server 下已有的 code（用于冲突检测，可空）
     */
    public static OpenApiImportPlan parse(String document, String baseUrlOverride, Set<String> existingCodes) {
        return new OpenApiToolParser().doParse(document, baseUrlOverride, existingCodes);
    }

    private OpenApiImportPlan doParse(String document, String baseUrlOverride, Set<String> existingCodes) {
        plan = new OpenApiImportPlan();

        if (document == null || document.trim().isEmpty()) {
            plan.addIssue(OpenApiIssue.error("document", "document 不能为空"));
            return finalizePlan();
        }

        try {
            root = JSON.parseObject(document, Feature.OrderedField);
        } catch (Exception e) {
            plan.addIssue(OpenApiIssue.error("document", "JSON 解析失败：" + e.getMessage()));
            return finalizePlan();
        }
        if (root == null) {
            plan.addIssue(OpenApiIssue.error("document", "document 必须是 JSON 对象"));
            return finalizePlan();
        }

        String openapi = trimToNull(root.getString("openapi"));
        if (openapi == null) {
            if (root.containsKey("swagger")) {
                plan.addIssue(OpenApiIssue.error("document",
                        "暂不支持 Swagger " + root.getString("swagger") + "，请先转换为 OpenAPI 3.x"));
            } else {
                plan.addIssue(OpenApiIssue.error("document", "缺少 openapi 版本字段（需为 3.x）"));
            }
            return finalizePlan();
        }
        if (!openapi.startsWith("3.")) {
            plan.addIssue(OpenApiIssue.error("document", "仅支持 OpenAPI 3.x，当前为 " + openapi));
            return finalizePlan();
        }
        plan.setOpenapiVersion(openapi);

        JSONObject info = root.getJSONObject("info");
        if (info != null) {
            plan.setSpecTitle(info.getString("title"));
            plan.setSpecVersion(info.getString("version"));
        }
        JSONObject comps = root.getJSONObject("components");
        if (comps != null) {
            components = comps;
        }

        String baseUrl = resolveBaseUrl(baseUrlOverride);
        if (baseUrl == null) {
            return finalizePlan();
        }
        plan.setBaseUrl(baseUrl);

        JSONObject paths = root.getJSONObject("paths");
        if (paths == null || paths.isEmpty()) {
            plan.addIssue(OpenApiIssue.error("document", "paths 为空，没有可导入的接口"));
            return finalizePlan();
        }

        Set<String> usedCodes = new HashSet<>();
        for (Map.Entry<String, Object> entry : paths.entrySet()) {
            String path = entry.getKey();
            if (path == null || path.isEmpty()) {
                continue;
            }
            if (!(entry.getValue() instanceof JSONObject)) {
                plan.addIssue(OpenApiIssue.warn("document", "路径 " + path + " 的定义不是对象，已跳过"));
                continue;
            }
            JSONObject pathItem = (JSONObject) entry.getValue();
            for (String method : HTTP_METHODS) {
                Object opObj = pathItem.get(method);
                if (!(opObj instanceof JSONObject)) {
                    continue;
                }
                plan.getTools().add(buildDraft(path, pathItem, method, (JSONObject) opObj, baseUrl, usedCodes, existingCodes));
            }
        }

        if (plan.getTools().isEmpty()) {
            plan.addIssue(OpenApiIssue.warn("document", "未找到任何 operation"));
        }
        return finalizePlan();
    }

    // ==================== 文档级 ====================

    private String resolveBaseUrl(String override) {
        String baseUrl = trimToNull(override);
        if (baseUrl == null) {
            JSONArray servers = root.getJSONArray("servers");
            if (servers != null && !servers.isEmpty()) {
                JSONObject server = servers.getJSONObject(0);
                if (server != null) {
                    baseUrl = trimToNull(server.getString("url"));
                    JSONObject vars = server.getJSONObject("variables");
                    if (baseUrl != null && vars != null) {
                        for (String name : vars.keySet()) {
                            JSONObject var = vars.getJSONObject(name);
                            String def = var == null ? null : var.getString("default");
                            if (def == null) {
                                plan.addIssue(OpenApiIssue.error("document",
                                        "servers[0].url 含变量 {" + name + "} 且无默认值，请填写 baseUrl"));
                                return null;
                            }
                            baseUrl = baseUrl.replace("{" + name + "}", def);
                        }
                    }
                }
            }
        }
        if (baseUrl == null) {
            plan.addIssue(OpenApiIssue.error("document", "缺少 servers[0].url，请填写 baseUrl"));
            return null;
        }
        baseUrl = baseUrl.trim();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (baseUrl.isEmpty()) {
            plan.addIssue(OpenApiIssue.error("document", "baseUrl 不能为空"));
            return null;
        }
        if (baseUrl.length() > MAX_URL) {
            plan.addIssue(OpenApiIssue.error("document", "baseUrl 超过 " + MAX_URL + " 字符"));
            return null;
        }
        try {
            URI uri = new URI(baseUrl);
            String scheme = uri.getScheme();
            if (scheme == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getHost().isEmpty()) {
                plan.addIssue(OpenApiIssue.error("document", "baseUrl 必须是 http(s) 绝对地址：" + baseUrl));
                return null;
            }
        } catch (URISyntaxException e) {
            plan.addIssue(OpenApiIssue.error("document", "baseUrl 非法：" + baseUrl));
            return null;
        }
        return baseUrl;
    }

    private OpenApiImportPlan finalizePlan() {
        int ready = 0;
        int conflicts = 0;
        int errors = 0;
        int warnings = 0;
        for (OpenApiToolDraft tool : plan.getTools()) {
            if (OpenApiToolDraft.ERROR.equals(tool.getStatus())) {
                errors++;
            } else if (OpenApiToolDraft.CONFLICT.equals(tool.getStatus())) {
                conflicts++;
            } else {
                ready++;
            }
            for (OpenApiIssue issue : tool.getIssues()) {
                if (OpenApiIssue.WARN.equals(issue.getLevel())) {
                    warnings++;
                }
            }
        }
        for (OpenApiIssue issue : plan.getIssues()) {
            if (OpenApiIssue.WARN.equals(issue.getLevel())) {
                warnings++;
            }
        }
        plan.setTotalOperations(plan.getTools().size());
        plan.setReady(ready);
        plan.setConflicts(conflicts);
        plan.setErrors(errors);
        plan.setWarnings(warnings);
        return plan;
    }

    // ==================== 单个 operation ====================

    private OpenApiToolDraft buildDraft(String path, JSONObject pathItem, String method, JSONObject op,
                                        String baseUrl, Set<String> usedCodes, Set<String> existingCodes) {
        OpenApiToolDraft draft = new OpenApiToolDraft();
        List<OpenApiIssue> issues = draft.getIssues();
        String upperMethod = method.toUpperCase(Locale.ROOT);
        String scope = upperMethod + " " + path;
        draft.setMethod(upperMethod);
        draft.setPath(path);
        String specVersion = trimToNull(plan.getSpecVersion());
        draft.setVersion(specVersion != null ? truncate(specVersion, MAX_VERSION) : "1.0.0");

        if (!SUPPORTED_METHODS.contains(method)) {
            issues.add(OpenApiIssue.error(scope, "网关运行时仅支持 GET/POST，暂不支持 " + upperMethod));
        }
        if (Boolean.TRUE.equals(op.getBoolean("deprecated"))) {
            issues.add(OpenApiIssue.warn(scope, "该 operation 已标记 deprecated"));
        }

        // ---- 工具标识 ----
        String opId = trimToNull(op.getString("operationId"));
        String rawCode = opId != null ? opId : method + "_" + path;
        String code = sanitizeCode(rawCode);
        if (opId == null) {
            issues.add(OpenApiIssue.warn(scope, "缺少 operationId，使用 " + code + " 作为工具标识"));
        }
        if (code.length() > MAX_CODE) {
            code = code.substring(0, MAX_CODE);
            issues.add(OpenApiIssue.warn(scope, "工具标识过长，已截断为 " + code));
        }
        String unique = code;
        int seq = 2;
        while (usedCodes.contains(unique)) {
            unique = code + "_" + seq++;
        }
        if (!unique.equals(code)) {
            issues.add(OpenApiIssue.warn(scope, "工具标识重复，已重命名为 " + unique));
        }
        usedCodes.add(unique);
        draft.setCode(unique);

        // ---- 名称/描述/标签 ----
        String summary = trimToNull(op.getString("summary"));
        String description = trimToNull(op.getString("description"));
        draft.setDescription(description != null ? description : (summary != null ? summary : unique));
        draft.setName(truncate(summary != null ? summary : unique, MAX_NAME));

        JSONArray tagArray = op.getJSONArray("tags");
        StringBuilder tagBuilder = new StringBuilder();
        if (tagArray != null) {
            for (Object tag : tagArray) {
                if (tag == null) {
                    continue;
                }
                if (tagBuilder.length() > 0) {
                    tagBuilder.append(',');
                }
                tagBuilder.append(tag);
            }
        }
        draft.setTags(truncate(tagBuilder.toString(), MAX_TAGS));

        // ---- 下游地址 ----
        String serviceUrl = baseUrl + (path.startsWith("/") ? path : "/" + path);
        if (serviceUrl.length() > MAX_URL) {
            issues.add(OpenApiIssue.error(scope, "serviceUrl 超过 " + MAX_URL + " 字符"));
        }
        draft.setServiceUrl(serviceUrl);

        // ---- 参数映射与入参 schema ----
        JSONObject properties = new JSONObject();
        JSONObject queryMapping = new JSONObject();
        JSONObject pathMapping = new JSONObject();
        JSONObject headerMapping = new JSONObject();
        JSONObject bodyMapping = new JSONObject();
        Set<String> required = new LinkedHashSet<>();

        Map<String, JSONObject> params = mergeParameters(pathItem, op, issues, scope);
        Set<String> declaredPathParams = new LinkedHashSet<>();
        for (JSONObject p : params.values()) {
            if ("path".equals(trimToNull(p.getString("in")))) {
                declaredPathParams.add(p.getString("name"));
            }
        }
        Set<String> pathVars = new LinkedHashSet<>();
        Matcher pm = PATH_VAR.matcher(path);
        while (pm.find()) {
            pathVars.add(pm.group(1));
        }
        for (String v : pathVars) {
            if (!declaredPathParams.contains(v)) {
                issues.add(OpenApiIssue.warn(scope, "路径占位符 {" + v + "} 未在 parameters 中声明，已按 string 补全"));
                String key = sanitizeInputKey(v, issues, scope);
                properties.put(key, stringSchema());
                pathMapping.put(v, "{{input." + key + "}}");
                required.add(key);
            }
        }
        for (String v : declaredPathParams) {
            if (!pathVars.contains(v)) {
                issues.add(OpenApiIssue.warn(scope, "path 参数 " + v + " 未出现在路径中"));
            }
        }

        for (JSONObject p : params.values()) {
            String in = trimToNull(p.getString("in"));
            String name = trimToNull(p.getString("name"));
            JSONObject paramSchema = parameterSchema(p, issues, scope);
            String key = sanitizeInputKey(name, issues, scope);
            if (properties.containsKey(key)) {
                issues.add(OpenApiIssue.warn(scope, "入参名 " + name + " 与其他参数同名（in 不同），将共用同一个入参"));
            }
            JSONObject converted = convertSchema(paramSchema, 0, issues, scope);
            properties.put(key, converted);
            boolean isRequired = p.getBooleanValue("required") || "path".equals(in);
            if ("path".equals(in)) {
                pathMapping.put(name, "{{input." + key + "}}");
                required.add(key);
            } else if ("query".equals(in)) {
                queryMapping.put(name, "{{input." + key + "}}");
                if (isRequired) {
                    required.add(key);
                }
                String type = converted.getString("type");
                if ("array".equals(type) || "object".equals(type)) {
                    issues.add(OpenApiIssue.warn(scope,
                            "query 参数 " + name + " 为 " + type + "，网关按单值拼接，序列化方式可能与下游不一致"));
                }
            } else if ("header".equals(in)) {
                headerMapping.put(name, "{{input." + key + "}}");
                if (isRequired) {
                    required.add(key);
                }
            } else if ("cookie".equals(in)) {
                issues.add(OpenApiIssue.warn(scope, "暂不支持 cookie 参数 " + name + "，已忽略"));
            } else {
                issues.add(OpenApiIssue.warn(scope, "未知参数位置 " + in + "（" + name + "），已忽略"));
            }
        }

        // ---- 请求体 ----
        JSONObject requestBody = op.getJSONObject("requestBody");
        if (requestBody != null) {
            JSONObject resolvedBody = deref(requestBody, issues, scope);
            JSONObject content = resolvedBody == null ? null : resolvedBody.getJSONObject("content");
            JSONObject bodySchema = pickBodySchema(content, issues, scope);
            if (bodySchema != null) {
                JSONObject effective = mergeAllOf(bodySchema, 0, issues, scope);
                JSONObject bodyProps = effective.getJSONObject("properties");
                if (bodyProps != null && !bodyProps.isEmpty()) {
                    for (Map.Entry<String, Object> pe : bodyProps.entrySet()) {
                        String prop = pe.getKey();
                        String key = sanitizeInputKey(prop, issues, scope);
                        if (properties.containsKey(key)) {
                            issues.add(OpenApiIssue.warn(scope,
                                    "请求体字段 " + prop + " 与参数同名，将共用同一个入参"));
                        }
                        JSONObject propSchema = pe.getValue() instanceof JSONObject
                                ? (JSONObject) pe.getValue() : null;
                        properties.put(key, convertSchema(propSchema, 1, issues, scope));
                        bodyMapping.put(prop, "{{input." + key + "}}");
                    }
                    JSONArray bodyRequired = effective.getJSONArray("required");
                    if (bodyRequired != null) {
                        for (Object r : bodyRequired) {
                            if (r != null) {
                                required.add(sanitizeInputKey(String.valueOf(r), issues, scope));
                            }
                        }
                    }
                } else {
                    boolean hasOtherMappings = !pathMapping.isEmpty()
                            || !queryMapping.isEmpty() || !headerMapping.isEmpty();
                    if (hasOtherMappings) {
                        issues.add(OpenApiIssue.error(scope,
                                "请求体是自由格式（无 properties），与 path/query/header 参数并存时无法生成 body 映射"));
                    } else {
                        issues.add(OpenApiIssue.warn(scope,
                                "请求体是自由格式（无 properties），调用参数将整体作为 JSON body 透传"));
                    }
                }
            }
        }

        // ---- 鉴权 ----
        applySecurity(op, draft, issues, scope);

        // ---- 汇总 ----
        JSONObject inputSchema = new JSONObject();
        inputSchema.put("type", "object");
        if (!properties.isEmpty()) {
            inputSchema.put("properties", properties);
        }
        if (!required.isEmpty()) {
            inputSchema.put("required", new ArrayList<>(required));
        }
        draft.setInputSchema(inputSchema);

        JSONObject mapping = new JSONObject();
        if (!pathMapping.isEmpty()) {
            mapping.put("path", pathMapping);
        }
        if (!queryMapping.isEmpty()) {
            mapping.put("query", queryMapping);
        }
        if (!headerMapping.isEmpty()) {
            mapping.put("header", headerMapping);
        }
        if (!bodyMapping.isEmpty()) {
            mapping.put("body", bodyMapping);
        }
        draft.setParamMapping(mapping);

        boolean hasError = false;
        for (OpenApiIssue issue : issues) {
            if (OpenApiIssue.ERROR.equals(issue.getLevel())) {
                hasError = true;
                break;
            }
        }
        if (hasError) {
            draft.setStatus(OpenApiToolDraft.ERROR);
            draft.setImportable(false);
        } else if (existingCodes != null && existingCodes.contains(unique)) {
            draft.setStatus(OpenApiToolDraft.CONFLICT);
            draft.setImportable(false);
            issues.add(OpenApiIssue.warn(scope,
                    "该 Server 下已存在 code=" + unique + "（导入时按冲突策略跳过或覆盖）"));
        } else {
            draft.setStatus(OpenApiToolDraft.READY);
            draft.setImportable(true);
        }
        return draft;
    }

    private void applySecurity(JSONObject op, OpenApiToolDraft draft, List<OpenApiIssue> issues, String scope) {
        JSONArray security = op.containsKey("security") ? op.getJSONArray("security") : root.getJSONArray("security");
        if (security == null || security.isEmpty()) {
            return;
        }
        JSONObject requirement = security.getJSONObject(0);
        if (requirement == null || requirement.isEmpty()) {
            return;
        }
        if (requirement.size() > 1) {
            issues.add(OpenApiIssue.warn(scope, "security 同时要求多个 scheme（AND 语义），仅按第一个生成鉴权"));
        }
        String schemeName = requirement.keySet().iterator().next();
        JSONObject schemes = components.getJSONObject("securitySchemes");
        JSONObject scheme = schemes == null ? null : schemes.getJSONObject(schemeName);
        if (scheme == null) {
            issues.add(OpenApiIssue.warn(scope, "引用的 securityScheme 不存在：" + schemeName));
            return;
        }
        String type = trimToNull(scheme.getString("type"));
        if ("apiKey".equals(type)) {
            String in = trimToNull(scheme.getString("in"));
            if ("header".equals(in)) {
                draft.setAuthType("API_KEY");
                JSONObject cfg = new JSONObject();
                cfg.put("header_name", scheme.getString("name"));
                cfg.put("api_key", "");
                draft.setAuthConfig(cfg);
                issues.add(OpenApiIssue.warn(scope,
                        "已按 API_KEY 生成鉴权（header=" + scheme.getString("name") + "），api_key 需保存后手工填写"));
            } else {
                issues.add(OpenApiIssue.warn(scope,
                        "apiKey 位于 " + in + "，网关暂不支持该位置，请手工配置鉴权"));
            }
        } else if ("http".equals(type)) {
            String httpScheme = trimToNull(scheme.getString("scheme"));
            if ("bearer".equalsIgnoreCase(httpScheme)) {
                draft.setAuthType("BEARER");
                JSONObject cfg = new JSONObject();
                cfg.put("token", "");
                draft.setAuthConfig(cfg);
                issues.add(OpenApiIssue.warn(scope, "已按 BEARER 生成鉴权，token 需保存后手工填写"));
            } else {
                issues.add(OpenApiIssue.warn(scope,
                        "HTTP " + httpScheme + " 鉴权暂不支持自动配置，请手工填写"));
            }
        } else if ("oauth2".equals(type) || "openIdConnect".equals(type)) {
            issues.add(OpenApiIssue.warn(scope, "OAuth2/OpenIdConnect 鉴权暂不支持自动配置，请手工填写"));
        } else {
            issues.add(OpenApiIssue.warn(scope, "未知 securityScheme 类型：" + type));
        }
    }

    // ==================== 参数 ====================

    private Map<String, JSONObject> mergeParameters(JSONObject pathItem, JSONObject op,
                                                    List<OpenApiIssue> issues, String scope) {
        Map<String, JSONObject> merged = new LinkedHashMap<>();
        addParameters(pathItem.getJSONArray("parameters"), merged, issues, scope);
        addParameters(op.getJSONArray("parameters"), merged, issues, scope);
        return merged;
    }

    private void addParameters(JSONArray array, Map<String, JSONObject> into,
                               List<OpenApiIssue> issues, String scope) {
        if (array == null) {
            return;
        }
        for (Object o : array) {
            if (!(o instanceof JSONObject)) {
                continue;
            }
            JSONObject p = deref((JSONObject) o, issues, scope);
            if (p == null) {
                continue;
            }
            String in = trimToNull(p.getString("in"));
            String name = trimToNull(p.getString("name"));
            if (in == null || name == null) {
                issues.add(OpenApiIssue.warn(scope, "参数缺少 name/in，已忽略"));
                continue;
            }
            into.put(in + "|" + name, p);
        }
    }

    private JSONObject stringSchema() {
        JSONObject schema = new JSONObject();
        schema.put("type", "string");
        return schema;
    }

    private JSONObject parameterSchema(JSONObject param, List<OpenApiIssue> issues, String scope) {
        JSONObject schema = param.getJSONObject("schema");
        if (schema != null) {
            return schema;
        }
        JSONObject content = param.getJSONObject("content");
        if (content != null && !content.isEmpty()) {
            for (String ct : content.keySet()) {
                JSONObject media = content.getJSONObject(ct);
                if (media != null && media.getJSONObject("schema") != null) {
                    return media.getJSONObject("schema");
                }
            }
        }
        issues.add(OpenApiIssue.warn(scope, "参数 " + param.getString("name") + " 缺少 schema，按 string 处理"));
        return null;
    }

    private JSONObject pickBodySchema(JSONObject content, List<OpenApiIssue> issues, String scope) {
        if (content == null || content.isEmpty()) {
            issues.add(OpenApiIssue.warn(scope, "requestBody 缺少 content，已忽略请求体"));
            return null;
        }
        String jsonKey = null;
        String jsonType = null;
        for (String ct : content.keySet()) {
            String base = ct.split(";")[0].trim().toLowerCase(Locale.ROOT);
            if ("application/json".equals(base) || base.endsWith("+json")) {
                jsonKey = ct;
                jsonType = base;
                break;
            }
        }
        if (jsonKey == null) {
            String first = content.keySet().iterator().next();
            String base = first.split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (base.startsWith("multipart/") || base.contains("x-www-form-urlencoded")) {
                issues.add(OpenApiIssue.error(scope, "暂不支持 " + base + " 请求体，请改用 application/json"));
                return null;
            }
            issues.add(OpenApiIssue.warn(scope, "请求体按首个 content 类型 " + base + " 解析"));
            jsonKey = first;
            jsonType = base;
        }
        JSONObject media = content.getJSONObject(jsonKey);
        JSONObject schema = media == null ? null : media.getJSONObject("schema");
        if (schema == null) {
            issues.add(OpenApiIssue.warn(scope, "请求体（" + jsonType + "）缺少 schema，已忽略请求体"));
        }
        return schema;
    }

    // ==================== $ref / allOf ====================

    private JSONObject deref(JSONObject node, List<OpenApiIssue> issues, String scope) {
        if (node == null || node.getString("$ref") == null) {
            return node;
        }
        JSONObject current = node;
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < 10 && current != null && current.getString("$ref") != null; i++) {
            String ref = current.getString("$ref");
            if (!seen.add(ref)) {
                issues.add(OpenApiIssue.warn(scope, "检测到循环 $ref：" + ref));
                return new JSONObject();
            }
            JSONObject target = resolvePointer(ref, issues, scope);
            if (target == null) {
                return new JSONObject();
            }
            JSONObject copy = new JSONObject(target);
            for (String k : current.keySet()) {
                if (!"$ref".equals(k)) {
                    copy.put(k, current.get(k));
                }
            }
            current = copy;
        }
        return current == null ? new JSONObject() : current;
    }

    private JSONObject resolvePointer(String ref, List<OpenApiIssue> issues, String scope) {
        if (ref == null || !ref.startsWith("#/")) {
            issues.add(OpenApiIssue.error(scope, "不支持外部 $ref：" + ref));
            return null;
        }
        Object current = root;
        for (String part : ref.substring(2).split("/")) {
            String key = part.replace("~1", "/").replace("~0", "~");
            if (current instanceof JSONObject) {
                current = ((JSONObject) current).get(key);
            } else if (current instanceof JSONArray) {
                try {
                    current = ((JSONArray) current).get(Integer.parseInt(key));
                } catch (Exception e) {
                    current = null;
                }
            } else {
                current = null;
            }
            if (current == null) {
                break;
            }
        }
        if (!(current instanceof JSONObject)) {
            issues.add(OpenApiIssue.error(scope, "无法解析 $ref：" + ref));
            return null;
        }
        return (JSONObject) current;
    }

    /** 合并 allOf（properties 取并集、required 取并集），并解析 $ref */
    private JSONObject mergeAllOf(JSONObject schema, int depth, List<OpenApiIssue> issues, String scope) {
        if (schema == null) {
            return new JSONObject();
        }
        if (depth > MAX_DEPTH) {
            issues.add(OpenApiIssue.warn(scope, "schema 嵌套过深（疑似循环 $ref），已截断"));
            return new JSONObject();
        }
        JSONObject resolved = deref(schema, issues, scope);
        JSONArray allOf = resolved.getJSONArray("allOf");
        if (allOf == null || allOf.isEmpty()) {
            return resolved;
        }
        JSONObject merged = new JSONObject();
        JSONObject properties = new JSONObject();
        JSONArray required = new JSONArray();
        for (Object o : allOf) {
            if (!(o instanceof JSONObject)) {
                continue;
            }
            mergeInto(merged, mergeAllOf((JSONObject) o, depth + 1, issues, scope), properties, required);
        }
        JSONObject siblings = new JSONObject(resolved);
        siblings.remove("allOf");
        mergeInto(merged, siblings, properties, required);
        if (!properties.isEmpty()) {
            merged.put("properties", properties);
        }
        if (!required.isEmpty()) {
            merged.put("required", required);
        }
        return merged;
    }

    private void mergeInto(JSONObject dest, JSONObject src, JSONObject properties, JSONArray required) {
        if (src == null) {
            return;
        }
        for (String key : src.keySet()) {
            Object value = src.get(key);
            if ("properties".equals(key) && value instanceof JSONObject) {
                JSONObject props = (JSONObject) value;
                for (String pk : props.keySet()) {
                    properties.put(pk, props.get(pk));
                }
            } else if ("required".equals(key) && value instanceof JSONArray) {
                for (Object r : (JSONArray) value) {
                    if (!required.contains(r)) {
                        required.add(r);
                    }
                }
            } else if (!"allOf".equals(key) && (!dest.containsKey(key) || dest.get(key) == null)) {
                dest.put(key, value);
            }
        }
    }

    // ==================== Schema 转换 ====================

    private JSONObject convertSchema(JSONObject raw, int depth, List<OpenApiIssue> issues, String scope) {
        if (raw == null) {
            return new JSONObject();
        }
        if (depth > MAX_DEPTH) {
            issues.add(OpenApiIssue.warn(scope, "schema 嵌套过深（疑似循环 $ref），已截断"));
            return new JSONObject();
        }
        JSONObject schema = mergeAllOf(raw, depth, issues, scope);
        JSONObject out = new JSONObject();

        Object typeObj = schema.get("type");
        String scalarType = null;
        if (typeObj instanceof String) {
            scalarType = (String) typeObj;
        } else if (typeObj instanceof JSONArray) {
            for (Object t : (JSONArray) typeObj) {
                if (t != null && !"null".equals(String.valueOf(t))) {
                    scalarType = String.valueOf(t);
                    break;
                }
            }
        }
        if (scalarType != null) {
            if (Boolean.TRUE.equals(schema.getBoolean("nullable"))) {
                JSONArray types = new JSONArray();
                types.add(scalarType);
                types.add("null");
                out.put("type", types);
            } else {
                out.put("type", scalarType);
            }
        }
        for (String key : SCHEMA_KEYS) {
            if (schema.containsKey(key) && schema.get(key) != null) {
                out.put(key, schema.get(key));
            }
        }
        JSONObject props = schema.getJSONObject("properties");
        if (props != null && !props.isEmpty()) {
            JSONObject outProps = new JSONObject();
            for (Map.Entry<String, Object> e : props.entrySet()) {
                JSONObject child = e.getValue() instanceof JSONObject ? (JSONObject) e.getValue() : null;
                outProps.put(e.getKey(), convertSchema(child, depth + 1, issues, scope));
            }
            out.put("properties", outProps);
        }
        JSONArray required = schema.getJSONArray("required");
        if (required != null && !required.isEmpty()) {
            out.put("required", required);
        }
        Object items = schema.get("items");
        if (items instanceof JSONObject) {
            out.put("items", convertSchema((JSONObject) items, depth + 1, issues, scope));
        } else if (items instanceof JSONArray) {
            JSONArray tuple = new JSONArray();
            for (Object it : (JSONArray) items) {
                if (it instanceof JSONObject) {
                    tuple.add(convertSchema((JSONObject) it, depth + 1, issues, scope));
                }
            }
            out.put("items", tuple);
        }
        Object additional = schema.get("additionalProperties");
        if (additional instanceof Boolean) {
            out.put("additionalProperties", additional);
        } else if (additional instanceof JSONObject) {
            out.put("additionalProperties", convertSchema((JSONObject) additional, depth + 1, issues, scope));
        }
        for (String union : new String[]{"oneOf", "anyOf"}) {
            JSONArray unionArr = schema.getJSONArray(union);
            if (unionArr == null || unionArr.isEmpty()) {
                continue;
            }
            JSONArray converted = new JSONArray();
            for (Object o : unionArr) {
                if (o instanceof JSONObject) {
                    converted.add(convertSchema((JSONObject) o, depth + 1, issues, scope));
                }
            }
            if (!converted.isEmpty()) {
                out.put(union, converted);
            }
        }
        return out;
    }

    // ==================== 工具方法 ====================

    private String sanitizeCode(String raw) {
        String s = raw == null ? "" : raw.trim();
        s = s.replaceAll("[^A-Za-z0-9_-]+", "_");
        s = s.replaceAll("_+", "_");
        s = s.replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "tool" : s;
    }

    private String sanitizeInputKey(String name, List<OpenApiIssue> issues, String scope) {
        if (name == null || name.indexOf('.') < 0) {
            return name;
        }
        String fixed = name.replace('.', '_');
        issues.add(OpenApiIssue.warn(scope,
                "入参名 " + name + " 含 '.'，已改为 " + fixed + "（模板路径中 '.' 为分隔符）"));
        return fixed;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
