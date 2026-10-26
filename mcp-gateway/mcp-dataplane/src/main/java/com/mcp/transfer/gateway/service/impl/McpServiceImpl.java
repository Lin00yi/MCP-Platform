package com.mcp.transfer.gateway.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcp.transfer.shared.utils.ReflectMethodInvoker;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.mcp.transfer.shared.dto.mcp.req.McpRequest;
import com.mcp.transfer.shared.dto.mcp.req.Params;
import com.mcp.transfer.shared.dto.mcp.res.McpResponse;
import com.mcp.transfer.shared.dto.mcp.result.data.McpResData;
import com.mcp.transfer.shared.entity.McpPrompt;
import com.mcp.transfer.shared.entity.McpResource;
import com.mcp.transfer.shared.entity.McpRequestLog;
import com.mcp.transfer.shared.entity.McpServer;
import com.mcp.transfer.shared.entity.McpTool;
import com.mcp.transfer.shared.entity.ServiceSystem;
import com.mcp.transfer.shared.entity.ToolCallRecord;
import com.mcp.transfer.shared.enums.McpErrorCode;
import com.mcp.transfer.shared.enums.McpMethodEnum;
import com.mcp.transfer.exposure.AssetAccessPolicy;
import com.mcp.transfer.exposure.AssetContentLimiter;
import com.mcp.transfer.exposure.MetaToolFactory;
import com.mcp.transfer.exposure.ToolAccessPolicy;
import com.mcp.transfer.exposure.ToolDefinitionSupport;
import com.mcp.transfer.exposure.ToolExposureModes;
import com.mcp.transfer.exposure.ToolExposureProperties;
import com.mcp.transfer.exposure.ToolPager;
import com.mcp.transfer.exposure.ToolResultService;
import com.mcp.transfer.exposure.ToolSchemaCompactor;
import com.mcp.transfer.exposure.ToolSchemaTrimmer;
import com.mcp.transfer.exposure.ToolSearchService;
import com.mcp.transfer.shared.mapper.McpPromptMapper;
import com.mcp.transfer.shared.mapper.McpRequestLogMapper;
import com.mcp.transfer.shared.mapper.McpResourceMapper;
import com.mcp.transfer.shared.mapper.McpServerMapper;
import com.mcp.transfer.shared.mapper.McpToolMapper;
import com.mcp.transfer.shared.mapper.ServiceSystemMapper;
import com.mcp.transfer.gateway.log.ToolCallRecordWriter;
import com.mcp.transfer.gateway.service.McpService;
import com.mcp.transfer.config.session.McpSessionCache;
import com.mcp.transfer.health.UpstreamCircuitBreaker;
import com.mcp.transfer.identity.IdentityProperties;
import com.mcp.transfer.identity.McpIdentityContext;
import com.mcp.transfer.identity.ResolvedIdentity;
import com.mcp.transfer.shared.utils.SecretCrypto;
import com.mcp.transfer.shared.utils.ServerKeyUtils;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @Title: McpServiceImpl
 * @Package: com.mcp.transfer.service.impl
 * @Description: mcp核心公共方法（多租户：server → tool/prompt/resource）
 * @author: zhangXu
 * @date: 2026/1/12 11:25
 * @version: V1.0
 */
@Service
public class McpServiceImpl implements McpService {

    private static final Logger logger = LoggerFactory.getLogger(McpServiceImpl.class);

    /**
     * JSON Schema 工厂（Draft-07），用于校验工具调用参数
     */
    private static final JsonSchemaFactory JSON_SCHEMA_FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

    /**
     * 用于 fastjson JSONObject 与 Jackson JsonNode 之间的转换
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 已编译 JSON Schema 缓存（key = inputSchema 原文；schema 变更时 key 随之变化，天然失效）
     */
    private final Map<String, JsonSchema> schemaCache = new ConcurrentHashMap<>();

    @Autowired
    private McpServerMapper mcpServerMapper;

    @Autowired
    private McpToolMapper mcpToolMapper;

    @Autowired
    private ToolAccessPolicy toolAccessPolicy;

    @Autowired
    private AssetAccessPolicy assetAccessPolicy;

    @Autowired
    private AssetContentLimiter assetContentLimiter;

    @Autowired
    private ToolResultService toolResultService;

    @Autowired
    private UpstreamCircuitBreaker circuitBreaker;

    @Autowired
    private ToolExposureProperties toolExposureProperties;

    @Autowired
    private ToolSchemaTrimmer toolSchemaTrimmer;

    @Autowired
    private ToolSearchService toolSearchService;


    @Autowired
    private com.mcp.transfer.exposure.ToolPaginationTelemetry toolPaginationTelemetry;

    @Autowired
    private com.mcp.transfer.exposure.TokenEstimator tokenEstimator;

    @Autowired
    private com.mcp.transfer.shared.config.security.UrlSecurityValidator urlSecurityValidator;

    @Autowired
    private McpPromptMapper mcpPromptMapper;

    @Autowired
    private McpResourceMapper mcpResourceMapper;

    @Autowired
    private ServiceSystemMapper serviceSystemMapper;

    @Autowired
    private ToolCallRecordWriter toolCallRecordWriter;

    @Autowired
    private McpRequestLogMapper mcpRequestLogMapper;

    @Autowired
    private ReflectMethodInvoker reflectMethodInvoker;

    @Autowired
    private SecretCrypto secretCrypto;

    @Autowired
    private com.mcp.transfer.gateway.McpUpstreamClient mcpUpstreamClient;

    @Autowired
    private McpSessionCache mcpSessionCache;

    @Autowired(required = false)
    private IdentityProperties identityProperties;

    private final WebClient webClient;

    /** 外部调用超时：数据面 30s，避免下游挂起拖死 Tomcat 线程 */
    private static final Duration DATA_TIMEOUT = Duration.ofSeconds(30);
    /** 测试连接超时 */
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(5);

    private static final int SCHEMA_CACHE_MAX = 2000;

    /** 日志落库单字段最大字符数（LONGTEXT 足够，但避免写入超大响应） */
    private static final int MAX_LOG_TEXT_LENGTH = 60000;

    /** 目录模式单次最多列出的工具数，避免一次返回过多摘要 */
    private static final int CATALOG_PAGE = 200;

    /** get_tool_schema 批量上限 */
    private static final int MAX_SCHEMA_BATCH = 10;

    /**
     * 保留字段：MCP 协议要求 arguments 必须是 object，LLM 会把下游期望的裸数组包一层 jsonArr 传递
     * MCP 层在校验时直接用 object schema 比对（包含 jsonArr 属性），构建下游 body 时剥掉这层壳取裸数组
     */
    private static final String UNWRAP_KEY = "jsonArr";

    /** 参数映射模板：{{input.xxx}} */
    private static final java.util.regex.Pattern JAVA_TEMPLATE_PATTERN =
            java.util.regex.Pattern.compile("\\{\\{([^{}]+)\\}\\}");

    public McpServiceImpl() {
        this.webClient = WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(20 * 1024 * 1024))
                .build();
    }

    /** 真实工具执行（本地反射 / HTTP 转发）+ 调用日志，标准 tools/call 与 META call_tool 共用 */
    @SneakyThrows
    @Override
    public Object executeToolOnServer(McpServer server, McpTool tool, McpRequest mcpRequest, HttpServletRequest request) {
        long start = System.currentTimeMillis();
        boolean success = true;
        String errorMsg = null;
        Object result = null;
        String callType = null;
        String httpMethod = null;
        String targetUrl = null;

        String appId = appIdOf(request);
        // 只有 META 模式才暴露 read_result_chunk；Endpoint 聚合层可显式覆盖
        boolean chunkable = isMetaExposure(server)
                || (request != null && Boolean.TRUE.equals(request.getAttribute("mcpResultChunkable")));
        String scope = "server:" + server.getId();

        // 熔断：连续失败达到阈值后直接短路，不再等待下游超时
        if (server.getId() != null && !circuitBreaker.allow(server.getId())) {
            logger.warn("工具调用被熔断: server={}, tool={}", server.getName(), tool.getCode());
            return errorResult(circuitBreaker.reason(server.getId()));
        }
        boolean callStarted = false;
        try {
            // 参数校验：根据 inputSchema 检查 arguments
            JSONObject rawArgs = mcpRequest.getParams() != null ? mcpRequest.getParams().getArguments() : null;
            validateArguments(tool, rawArgs);

            if (tool.getType() != null && tool.getType() == 0) {
                callType = "LOCAL";
                callStarted = true;
                result = reflectMethodInvoker.invokeMethod(tool.getServiceUrl(), mcpRequest);
                circuitBreaker.recordSuccess(server.getId());
                return toolResultService.present(appId, scope, result, chunkable);
            }
            callType = "EXTERNAL";
            httpMethod = tool.getRequestType();
            targetUrl = tool.getServiceUrl();
            callStarted = true;
            result = callExternalService(tool, mcpRequest, request);
            circuitBreaker.recordSuccess(server.getId());
            return toolResultService.present(appId, scope, textResult(result), chunkable);
        } catch (Exception e) {
            success = false;
            errorMsg = e.getMessage();
            if (callStarted) {
                circuitBreaker.recordFailure(server.getId());
            }
            throw e;
        } catch (Throwable t) {
            // Error级别异常也要更新状态并写入tool_call_record
            success = false;
            errorMsg = "[Error]" + t.getClass().getName() + ": " + t.getMessage();
            if (callStarted) {
                circuitBreaker.recordFailure(server.getId());
            }
            throw t;
        } finally {
            saveToolCallRecord(server, tool, mcpRequest, result, callType, httpMethod, targetUrl,
                    success, errorMsg, start, request);
        }
    }

    /**
     * META 模式元工具分发：search_tools / get_tool_schema / call_tool / read_result_chunk。
     */
    private Object callMetaTool(McpServer server, String name, McpRequest mcpRequest,
                                HttpServletRequest request, String appId) {
        JSONObject args = mcpRequest.getParams().getArguments() == null
                ? new JSONObject() : mcpRequest.getParams().getArguments();
        if (MetaToolFactory.SEARCH_TOOLS.equals(name)) {
            return metaSearch(server, args, appId);
        }
        if (MetaToolFactory.GET_TOOL_SCHEMA.equals(name)) {
            return metaGetSchema(server, args, appId);
        }
        if (MetaToolFactory.CALL_TOOL.equals(name)) {
            return metaCall(server, args, request, appId);
        }
        if (MetaToolFactory.READ_RESULT_CHUNK.equals(name)) {
            return toolResultService.readChunk(appId, args);
        }
        return errorResult("未知元工具: " + name);
    }

    /**
     * search_tools：检索或按业务分组列目录；返回 name/description/tags，不返回 inputSchema。
     *
     * <p>策略由 {@code mcp.tools.search-mode} 决定；query 为空时默认走目录模式。
     */
    private Object metaSearch(McpServer server, JSONObject args, String appId) {
        List<McpTool> visible = toolAccessPolicy.visibleTools(server, appId, false);
        String domain = domainOf(server);
        List<ToolSearchService.ToolDoc> docs = new ArrayList<>();
        for (McpTool tool : visible) {
            docs.add(ToolSearchService.ToolDoc.of(tool, tool.getCode(), domain, server.getId()));
        }
        ToolSearchService.SearchRequest request =
                ToolSearchService.parseRequest(args, toolExposureProperties);

        ToolSearchService.Mode configured =
                ToolSearchService.modeOf(toolExposureProperties.getSearchMode());
        if (configured == ToolSearchService.Mode.CATALOG
                || (configured == ToolSearchService.Mode.AUTO && !request.hasQuery())) {
            return catalogResult(server, domain, docs, request);
        }
        if (configured != ToolSearchService.Mode.AUTO) {
            request.setMode(configured);
        }

        ToolSearchService.SearchResult result = toolSearchService.searchDocuments(docs, request);
        JSONArray items = searchItems(result.getHits());
        JSONObject payload = new JSONObject();
        payload.put("mode", result.getMode().name());
        payload.put("query", request.getQuery());
        payload.put("totalTools", visible.size());
        payload.put("returned", items.size());
        payload.put("tools", items);
        if (items.isEmpty()) {
            payload.put("hint", "没有匹配的工具。可以换更短的关键词、去掉 tags 过滤，"
                    + "或传 domain 查看某个业务分组。");
        }
        return textResult(payload.toJSONString());
    }

    /** 目录模式：无 domain 时给分组概览，指定 domain 时列出该分组工具摘要。 */
    private Object catalogResult(McpServer server, String domain,
                                 List<ToolSearchService.ToolDoc> docs,
                                 ToolSearchService.SearchRequest request) {
        List<ToolSearchService.ToolDoc> filtered =
                new ArrayList<>(toolSearchService.filterDocuments(docs, request));
        filtered.sort((left, right) -> String.valueOf(left.getExposedName())
                .compareTo(String.valueOf(right.getExposedName())));
        JSONObject payload = new JSONObject();
        payload.put("mode", ToolSearchService.Mode.CATALOG.name());
        payload.put("totalTools", filtered.size());
        if (StringUtils.hasText(request.getDomain())) {
            int limit = Math.min(filtered.size(), CATALOG_PAGE);
            JSONArray items = new JSONArray();
            for (int i = 0; i < limit; i++) {
                ToolSearchService.ToolDoc doc = filtered.get(i);
                JSONObject item = new JSONObject();
                item.put("name", doc.getExposedName());
                item.put("description", ToolDefinitionSupport.truncate(
                        doc.getDescription(), toolExposureProperties.getSearchDescriptionLength()));
                if (!doc.getTags().isEmpty()) {
                    item.put("tags", doc.getTags());
                }
                items.add(item);
            }
            payload.put("domain", domain);
            payload.put("returned", items.size());
            payload.put("tools", items);
            if (filtered.size() > limit) {
                payload.put("hint", "该分组工具较多，可加 query 或 tags 缩小范围。");
            }
        } else {
            JSONArray groups = new JSONArray();
            JSONObject group = new JSONObject();
            group.put("domain", domain);
            group.put("server", server.getName());
            group.put("toolCount", filtered.size());
            groups.add(group);
            payload.put("groups", groups);
            payload.put("hint", "传 domain=\"" + domain + "\" 列出该分组的工具，或直接传 query 检索。");
        }
        return textResult(payload.toJSONString());
    }

    private JSONArray searchItems(List<ToolSearchService.ScoredDoc> hits) {
        JSONArray items = new JSONArray();
        for (ToolSearchService.ScoredDoc hit : hits) {
            ToolSearchService.ToolDoc doc = hit.getDoc();
            JSONObject item = new JSONObject();
            item.put("name", doc.getExposedName());
            item.put("description", ToolDefinitionSupport.truncate(
                    doc.getDescription(), toolExposureProperties.getSearchDescriptionLength()));
            if (!doc.getTags().isEmpty()) {
                item.put("tags", doc.getTags());
            }
            if (StringUtils.hasText(doc.getDomain())) {
                item.put("domain", doc.getDomain());
            }
            items.add(item);
        }
        return items;
    }

    private String domainOf(McpServer server) {
        if (server == null) {
            return null;
        }
        if (ServerKeyUtils.isValid(server.getServerKey())) {
            return server.getServerKey();
        }
        return ServerKeyUtils.fallback(server.getName(), server.getId());
    }

    private String appIdOf(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object value = request.getAttribute("mcpAppId");
        return value == null ? null : value.toString();
    }

    /**
     * get_tool_schema：按需展开 inputSchema。
     *
     * <p>支持单个 name 与批量 tool_codes；detail_level=compact（默认）返回精简版，
     * full 返回入库完整版。返回体带 tokens 估算，便于观察上下文成本。
     */
    private Object metaGetSchema(McpServer server, JSONObject args, String appId) {
        String detailLevel = normalizeDetailLevel(args.getString("detail_level"));
        List<String> codes = schemaCodes(args);
        if (codes.isEmpty()) {
            return errorResult("get_tool_schema 需要 name 或 tool_codes 参数（search_tools 返回的工具名）");
        }
        List<McpTool> visible = toolAccessPolicy.visibleTools(server, appId, false);
        boolean batch = args.get("tool_codes") != null;

        if (!batch) {
            McpTool tool = toolSearchService.findByCode(visible, codes.get(0));
            if (tool == null) {
                return errorResult("工具不存在或当前应用无权访问: " + codes.get(0));
            }
            JSONObject payload = schemaPayload(server, tool, detailLevel);
            payload.put("detailLevel", detailLevel);
            return textResult(payload.toJSONString());
        }

        JSONArray schemas = new JSONArray();
        int limit = Math.min(codes.size(), MAX_SCHEMA_BATCH);
        for (int i = 0; i < limit; i++) {
            String code = codes.get(i);
            McpTool tool = toolSearchService.findByCode(visible, code);
            if (tool == null) {
                JSONObject missing = new JSONObject();
                missing.put("name", code);
                missing.put("error", "工具不存在或当前应用无权访问");
                schemas.add(missing);
            } else {
                schemas.add(schemaPayload(server, tool, detailLevel));
            }
        }
        JSONObject payload = new JSONObject();
        payload.put("detailLevel", detailLevel);
        payload.put("requested", codes.size());
        payload.put("returned", schemas.size());
        payload.put("schemas", schemas);
        return textResult(payload.toJSONString());
    }

    private JSONObject schemaPayload(McpServer server, McpTool tool, String detailLevel) {
        JSONObject item = new JSONObject();
        item.put("name", tool.getCode());
        item.put("server", domainOf(server));
        String description = ToolDefinitionSupport.description(tool);
        if ("compact".equals(detailLevel)) {
            description = ToolDefinitionSupport.truncate(description, 200);
        }
        item.put("description", description);
        JSONObject raw = ToolDefinitionSupport.inputSchema(tool);
        JSONObject schema = "compact".equals(detailLevel)
                ? ToolSchemaCompactor.compact(raw)
                : (raw == null ? new JSONObject() : raw);
        item.put("inputSchema", schema);
        item.put("tokens", tokenEstimator.estimate(schema.toJSONString()));
        return item;
    }

    private List<String> schemaCodes(JSONObject args) {
        List<String> codes = new ArrayList<>();
        Object raw = args.get("tool_codes");
        if (raw instanceof Iterable) {
            for (Object value : (Iterable<?>) raw) {
                if (value != null && StringUtils.hasText(String.valueOf(value))) {
                    codes.add(String.valueOf(value).trim());
                }
            }
        } else if (raw instanceof String && StringUtils.hasText((String) raw)) {
            for (String value : ((String) raw).split(",")) {
                if (StringUtils.hasText(value)) {
                    codes.add(value.trim());
                }
            }
        }
        if (codes.isEmpty() && StringUtils.hasText(args.getString("name"))) {
            codes.add(args.getString("name").trim());
        }
        return codes;
    }

    private String normalizeDetailLevel(String value) {
        return "full".equalsIgnoreCase(value) ? "full" : "compact";
    }

    /** call_tool：按 name + arguments 执行真实工具，复用标准校验、鉴权、日志链路 */
    private Object metaCall(McpServer server, JSONObject args, HttpServletRequest request, String appId) {
        String name = args.getString("name");
        if (!StringUtils.hasText(name)) {
            return errorResult("call_tool 需要 name 参数（目标工具名）");
        }
        if (MetaToolFactory.isMetaTool(name)) {
            return errorResult("call_tool 不能调用元工具本身，请直接调用 " + name);
        }
        // reason 只用于审批展示与审计，不传给目标工具
        String reason = args.getString("reason");
        if (StringUtils.hasText(reason)) {
            logger.info("call_tool reason, server={}, tool={}, reason={}",
                    server.getName(), name, reason);
        }
        Object nested = args.get("arguments");
        JSONObject callArgs = new JSONObject();
        if (nested != null) {
            // Jackson 反序列化 JSONObject 的嵌套值可能是 LinkedHashMap，不能只判断 JSONObject
            if (!(nested instanceof java.util.Map)) {
                return errorResult("call_tool.arguments 必须是 JSON 对象");
            }
            for (java.util.Map.Entry<?, ?> entry : ((java.util.Map<?, ?>) nested).entrySet()) {
                if (entry.getKey() != null) {
                    callArgs.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }

        List<McpTool> visible = toolAccessPolicy.visibleTools(server, appId, false);
        McpTool tool = toolSearchService.findByCode(visible, name);
        if (tool == null) {
            return errorResult("工具不存在或当前应用无权访问: " + name);
        }

        McpRequest sub = new McpRequest();
        Params params = new Params();
        params.setName(name);
        params.setArguments(callArgs);
        sub.setParams(params);

        try {
            Object result = executeToolOnServer(server, tool, sub, request);
            return result instanceof McpResData ? result : textResult(result);
        } catch (IllegalArgumentException e) {
            return errorResult(e.getMessage());
        } catch (Exception e) {
            logger.error("call_tool 执行失败, server={}, tool={}", server.getName(), name, e);
            return errorResult("工具调用失败: " + e.getMessage());
        }
    }

    private boolean isMetaExposure(McpServer server) {
        return toolExposureProperties.isMetaEnabled()
                && ToolExposureModes.EXPOSURE_META.equals(ToolExposureModes.exposureOf(server));
    }

    /** 构造 MCP 工具结果（content 单条 text） */
    private McpResData textResult(Object result) {
        String text;
        if (result == null) {
            text = "";
        } else if (result instanceof String) {
            text = (String) result;
        } else if (result instanceof McpResData) {
            return (McpResData) result;
        } else {
            text = JSON.toJSONString(result);
        }
        McpResData resData = new McpResData();
        List<JSONObject> content = new ArrayList<>();
        JSONObject item = new JSONObject();
        item.put("type", "text");
        item.put("text", text);
        content.add(item);
        resData.setContent(content);
        return resData;
    }

    /** 工具级错误（isError=true），让模型能读到可修正的信息 */
    private McpResData errorResult(String message) {
        McpResData resData = textResult(message);
        resData.setIsError(true);
        return resData;
    }

    /** 客编由用户信息服务决定，客户端/模型传入的客编字段一律覆盖，防止越权查询 */
    @Override
    public void overrideCustomerArgs(McpRequest mcpRequest) {
        ResolvedIdentity identity = McpIdentityContext.get();
        if (identity == null || identity.getCustomerId() == null || mcpRequest.getParams() == null) {
            return;
        }
        JSONObject args = mcpRequest.getParams().getArguments();
        if (args == null) {
            return;
        }
        for (String name : identityProperties.getCustomerArgNames().split(",")) {
            String key = name.trim();
            if (!key.isEmpty() && args.containsKey(key)) {
                args.put(key, identity.getCustomerId());
            }
        }
    }

    private void saveToolCallRecord(McpServer server, McpTool tool, McpRequest mcpRequest,
                                     Object result, String callType, String httpMethod, String targetUrl,
                                     boolean success, String errorMsg, long start, HttpServletRequest request) {
        try {
            ToolCallRecord record = new ToolCallRecord();
            record.setTenantId(server.getTenantId());
            // 租户名走本地缓存（10min TTL），避免每次工具调用多一次 SELECT
            record.setTenantName(toolCallRecordWriter.tenantName(server.getTenantId()));
            record.setServerId(server.getId());
            record.setServerName(server.getName());
            // 应用维度已移除；调用记录只保留用户与资产
            ResolvedIdentity identity = McpIdentityContext.get();
            record.setAppId(null);
            // 用户只认身份下传结果；不再接受客户端自报的 userId 请求头
            String userId = identity != null ? identity.getUserId() : null;
            String userName = identity != null ? identity.getUserName() : null;
            record.setUserId(cut(userId, 255));
            record.setUserName(cut(userName, 128));
            record.setMethodDefId(tool.getId());
            record.setMethodCode(tool.getCode());
            record.setMethodName(tool.getName());
            JSONObject args = mcpRequest.getParams().getArguments();
            String argsJson = args != null ? secretCrypto.maskJsonSecrets(args.toJSONString()) : null;
            record.setRequestArgs(truncate(argsJson, MAX_LOG_TEXT_LENGTH));
            String jsonResult;
            try {
                jsonResult = JSON.toJSONString(result);
            } catch (Exception e) {
                jsonResult = String.valueOf(result);
            }
            // 只做安全截断，不拼接会破坏 JSON 结构的尾巴
            record.setResponseResult(secretCrypto.maskJsonSecrets(truncate(jsonResult, MAX_LOG_TEXT_LENGTH)));

            record.setCallType(callType);
            record.setHttpMethod(httpMethod);
            record.setTargetUrl(targetUrl);
            record.setSuccess(success ? 1 : 0);
            record.setErrorMsg(errorMsg);
            record.setDuration(System.currentTimeMillis() - start);
            record.setCreateTime(new Date());
            // 异步批量写入：请求线程只入队，不等待 DB
            toolCallRecordWriter.offer(record);
        } catch (Exception e) {
            logger.error("记录工具调用日志失败, methodCode: {}", tool.getCode(), e);
        }
    }

    @Override
    public void saveMcpRequestLog(String method, String methodName, String requestPath, HttpServletRequest request, McpRequest mcpRequest, McpResponse response) {
        try {
            McpRequestLog log = new McpRequestLog();
            log.setMcpMethod(method);
            log.setMethodName(methodName);
            // 脱敏：不落明文 token；sk 用 SHA-256 哈希存储用于联表
            String authHeader = request.getHeader("Authorization");
            log.setAuthorization(secretCrypto.maskBearer(authHeader));
            log.setSkHash(secretCrypto.sha256Hex(secretCrypto.extractBearerToken(authHeader)));
            // 用户身份只取身份下传结果，不再读取客户端自报的 userId 请求头
            ResolvedIdentity identity = McpIdentityContext.get();
            log.setUserId(identity != null && StringUtils.hasText(identity.getUserId())
                    ? identity.getUserId() : null);
            log.setRequestParams(secretCrypto.maskJsonSecrets(JSON.toJSONString(mcpRequest)));
            log.setResponseResult(secretCrypto.maskJsonSecrets(truncate(JSON.toJSONString(response), 20000)));
            log.setCreateTime(new Date());
            // 记录业务方(租户)：根据请求路径解析对应 server → tenant
            try {
                McpServer server = mcpServerMapper.selectOneByPath(requestPath);
                if (server != null) {
                    log.setTenantId(server.getTenantId());
                } else {
                    Object aggregateTenant = request.getAttribute("mcpAggregateTenantId");
                    if (aggregateTenant instanceof Integer) {
                        log.setTenantId((Integer) aggregateTenant);
                    }
                }
            } catch (Exception ignore) {
            }
            mcpRequestLogMapper.insertSelective(log);
        } catch (Exception e) {
            logger.error("记录MCP请求日志失败, method: {}", method, e);
        }
    }

    /**
     * 工具注册前连通性测试：
     * - 本地工具：校验 serviceUrl 为 类名.方法名 格式；
     * - HTTP 工具：按 auth 配置实际发起一次 GET/POST，返回状态码/耗时/响应摘要。
     */
    @Override
    public Map<String, Object> testConnection(McpTool tool, String authorization) {
        Map<String, Object> result = new HashMap<>();
        long start = System.currentTimeMillis();
        try {
            if (tool.getType() != null && tool.getType() == 0) {
                String serviceUrl = tool.getServiceUrl();
                if (!StringUtils.hasText(serviceUrl) || !serviceUrl.contains(".")) {
                    result.put("ok", false);
                    result.put("error", "本地工具 serviceUrl 需为「类名.方法名」（至少一个点分隔）");
                    return result;
                }
                result.put("ok", true);
                result.put("note", "本地反射工具（格式正确，未实际执行）");
                return result;
            }

            // HTTP 转发
            if (!StringUtils.hasText(tool.getServiceUrl())) {
                result.put("ok", false);
                result.put("error", "HTTP 转发工具 serviceUrl 不能为空");
                return result;
            }
            Map<String, Object> auth = resolveAuth(tool, authorization);
            @SuppressWarnings("unchecked")
            Map<String, String> authHeaders = (Map<String, String>) auth.get("header");
            @SuppressWarnings("unchecked")
            Map<String, String> authParams = (Map<String, String>) auth.get("params");
            String url = buildUrlWithParams(tool.getServiceUrl(), null, authParams);
            // 测试连接时把 {占位符} 替换成示例值 1，避免 new URI 因 { } 非法字符报错（真实值在 tools/call 时按参数映射替换）
            url = url.replaceAll("\\{[^}]+\\}", "1");
            urlSecurityValidator.validate(url);

            String responseBody;
            if ("GET".equalsIgnoreCase(tool.getRequestType())) {
                responseBody = webClient.get().uri(new URI(url))
                        .headers(h -> h.setAll(authHeaders)).retrieve().bodyToMono(String.class).block(TEST_TIMEOUT);
            } else {
                responseBody = webClient.post().uri(new URI(url))
                        .header("Content-Type", "application/json")
                        .headers(h -> h.setAll(authHeaders)).bodyValue("{}").retrieve().bodyToMono(String.class).block(TEST_TIMEOUT);
            }
            result.put("ok", true);
            result.put("status", 200);
            result.put("body", truncate(responseBody, 500));
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            // 端点可达，但返回非 2xx
            result.put("ok", true);
            result.put("status", e.getRawStatusCode());
            result.put("note", "端点可达，但返回非 2xx（" + e.getRawStatusCode() + "）");
            result.put("body", truncate(e.getResponseBodyAsString(), 500));
        } catch (Exception e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
        } finally {
            result.put("duration", System.currentTimeMillis() - start);
        }
        return result;
    }

    /** 按列宽截断，不追加省略号（避免超出列定义长度导致插入失败） */
    private String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    /** 调用外部服务 */
    private Object callExternalService(McpTool tool, McpRequest mcpRequest, HttpServletRequest request) {
        JSONObject args = mcpRequest.getParams().getArguments();

        // 1. 鉴权（按 auth_type/auth_config 配置）
        Map<String, Object> auth = resolveAuth(tool, request == null ? null : request.getHeader("Authorization"));
        @SuppressWarnings("unchecked")
        Map<String, String> authHeaders = (Map<String, String>) auth.get("header");
        @SuppressWarnings("unchecked")
        Map<String, String> authParams = (Map<String, String>) auth.get("params");

        // 2. agentChannel 透传
        String agentChannel = request.getHeader("agentChannel");
        if (!StringUtils.hasText(agentChannel)) {
            agentChannel = "others";
        }
        authHeaders.put("agentChannel", agentChannel);

        // 2.1 身份下传：REST 工具默认不透传客编（避免泄露给公网 API）；显式开启后才注入
        if (identityProperties != null && identityProperties.isPropagateToRestTools()) {
            ResolvedIdentity identity = McpIdentityContext.get();
            if (identity != null) {
                authHeaders.put("X-MCP-User-Id", identity.getUserId());
                authHeaders.put("X-MCP-Customer-Id", identity.getCustomerId());
            }
        }

        // 3. 参数映射（可视化字段映射）
        Map<String, Object> mapping = resolveParamMapping(tool.getParamMapping(), args);
        @SuppressWarnings("unchecked")
        Map<String, String> queryMap = (Map<String, String>) mapping.get("query");
        @SuppressWarnings("unchecked")
        Map<String, String> pathMap = (Map<String, String>) mapping.get("path");
        @SuppressWarnings("unchecked")
        Map<String, String> headerMap = (Map<String, String>) mapping.get("header");
        Object mappedBody = mapping.get("body");
        boolean mapped = Boolean.TRUE.equals(mapping.get("mapped"));

        String url = tool.getServiceUrl();
        if (!StringUtils.hasText(url)) {
            throw new RuntimeException("外部服务地址未配置");
        }
        url = applyPathMapping(url, pathMap);

        // 合并 query（auth params + 映射 query）
        Map<String, String> finalQuery = new HashMap<>();
        if (authParams != null) {
            finalQuery.putAll(authParams);
        }
        finalQuery.putAll(queryMap);

        // 合并 header（auth header + 映射 header）
        Map<String, String> finalHeaders = new HashMap<>(authHeaders);
        finalHeaders.putAll(headerMap);

        boolean isGet = "GET".equalsIgnoreCase(tool.getRequestType());

        try {
            if (isGet) {
                // GET：未配置映射时默认 args 全量透传为 query
                if (!mapped && args != null) {
                    for (Map.Entry<String, Object> e : args.entrySet()) {
                        finalQuery.put(e.getKey(), String.valueOf(e.getValue()));
                    }
                }
                String fullUrl = buildUrlWithParams(url, null, finalQuery);
                urlSecurityValidator.validate(fullUrl);
                logger.info("外部GET请求 URL: {}", fullUrl);
                logger.info("外部GET请求 Header 名称: {}", finalHeaders.keySet());

                String responseBody = webClient.get()
                        .uri(new URI(fullUrl))
                        .headers(h -> h.setAll(finalHeaders))
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(DATA_TIMEOUT);

                logger.info("外部GET请求完成, 响应长度={}", responseBody == null ? 0 : responseBody.length());
                return responseBody;
            } else {
                String fullUrl = buildUrlWithParams(url, null, finalQuery);
                urlSecurityValidator.validate(fullUrl);
                boolean isForm = tool.getBodyType() != null && tool.getBodyType() == 1;

                if (isForm) {
                    JSONObject formArgs = (mappedBody instanceof JSONObject) ? (JSONObject) mappedBody : args;
                    String formBody = buildFormBody(formArgs);
                    logger.info("外部POST(FORM)请求 URL: {}", fullUrl);
                    logger.info("外部POST(FORM)请求 Header 名称: {}", finalHeaders.keySet());

                    String responseBody = webClient.post()
                            .uri(new URI(fullUrl))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .headers(h -> h.setAll(finalHeaders))
                            .bodyValue(formBody)
                            .retrieve()
                            .bodyToMono(String.class)
                            .block();

                    logger.info("外部POST(FORM)请求完成, 响应长度={}", responseBody == null ? 0 : responseBody.length());
                    return responseBody;
                } else {
                    Object bodyValue;
                    if (mapped) {
                        bodyValue = mappedBody;
                    } else {
                        bodyValue = unwrapJsonArrArgs(args);
                    }
                    String body = bodyValue != null ? JSON.toJSONString(bodyValue) : "";
                    logger.info("外部POST请求 URL: {}", fullUrl);
                    logger.info("外部POST请求 Body 长度: {}", body.length());

                    String responseBody = webClient.post()
                            .uri(new URI(fullUrl))
                            .header("Content-Type", "application/json")
                            .headers(h -> h.setAll(finalHeaders))
                            .bodyValue(body)
                            .retrieve()
                            .bodyToMono(String.class)
                            .block();

                    logger.info("外部POST请求完成, 响应长度={}", responseBody == null ? 0 : responseBody.length());
                    return responseBody;
                }
            }
        } catch (Exception e) {
            logger.error("调用外部服务失败, url: {}", url, e);
            throw new RuntimeException("调用外部服务失败: " + e.getMessage(), e);
        }
    }

    /**
     * 按工具的 auth_type/auth_config 解析下游鉴权，返回 {header: Map, params: Map}
     */
    private Map<String, Object> resolveAuth(McpTool tool, String authorization) {
        Map<String, String> headers = new HashMap<>();
        Map<String, String> params = null;
        String authType = tool.getAuthType();
        JSONObject authConfig = tool.getAuthConfig() != null ? JSON.parseObject(secretCrypto.decrypt(tool.getAuthConfig())) : null;

        boolean needSkExchange = "SK_EXCHANGE".equals(authType)
                || ((authType == null || "NONE".equals(authType)) && tool.getNeedToken() != null && tool.getNeedToken() == 1);

        if (needSkExchange) {
            // sk 换 token：优先用 auth_config.auth_url，否则回退 service_system 配置（system_id）
            String authUrl = authConfig != null ? authConfig.getString("auth_url") : null;
            if (!StringUtils.hasText(authUrl)) {
                ServiceSystem ss = serviceSystemMapper.selectByPrimaryKey(tool.getSystemId());
                authUrl = ss != null ? ss.getAuthUrl() : null;
            }
            JSONObject authInfo = skExchange(authUrl, authorization);
            JSONObject headerObj = authInfo.getJSONObject("header");
            JSONObject paramsObj = authInfo.getJSONObject("params");
            if (headerObj != null) {
                for (Map.Entry<String, Object> e : headerObj.entrySet()) {
                    headers.put(e.getKey(), e.getValue() != null ? e.getValue().toString() : "");
                }
            }
            if (paramsObj != null) {
                params = new HashMap<>();
                for (Map.Entry<String, Object> e : paramsObj.entrySet()) {
                    params.put(e.getKey(), e.getValue() != null ? e.getValue().toString() : "");
                }
            }
        } else if ("API_KEY".equals(authType)) {
            if (authConfig != null) {
                String headerName = authConfig.getString("header_name");
                if (!StringUtils.hasText(headerName)) {
                    headerName = "X-Api-Key";
                }
                headers.put(headerName, authConfig.getString("api_key"));
            }
        } else if ("BEARER".equals(authType)) {
            if (authConfig != null) {
                headers.put("Authorization", "Bearer " + authConfig.getString("token"));
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("header", headers);
        result.put("params", params);
        return result;
    }

    /**
     * 解析参数映射：{"query":{...},"path":{...},"header":{...},"body":{...}}
     * 支持 {{input.字段路径}} 模板引用；未配置映射时返回 mapped=false
     */
    private Map<String, Object> resolveParamMapping(String paramMapping, JSONObject args) {
        Map<String, Object> result = new HashMap<>();
        result.put("query", new HashMap<String, String>());
        result.put("path", new HashMap<String, String>());
        result.put("header", new HashMap<String, String>());
        result.put("body", null);
        result.put("mapped", false);

        if (!StringUtils.hasText(paramMapping)) {
            return result;
        }
        try {
            JSONObject mapping = JSON.parseObject(paramMapping);
            boolean mapped = false;
            JSONObject query = mapping.getJSONObject("query");
            if (query != null && !query.isEmpty()) {
                result.put("query", renderMap(query, args));
                mapped = true;
            }
            JSONObject path = mapping.getJSONObject("path");
            if (path != null && !path.isEmpty()) {
                result.put("path", renderMap(path, args));
                mapped = true;
            }
            JSONObject header = mapping.getJSONObject("header");
            if (header != null && !header.isEmpty()) {
                result.put("header", renderMap(header, args));
                mapped = true;
            }
            if (mapping.containsKey("body")) {
                result.put("body", renderValue(mapping.get("body"), args));
                mapped = true;
            }
            result.put("mapped", mapped);
        } catch (Exception e) {
            logger.warn("参数映射解析失败，使用默认透传, paramMapping={}", paramMapping, e);
        }
        return result;
    }

    /** 渲染映射 Map（值转字符串） */
    private Map<String, String> renderMap(JSONObject obj, JSONObject args) {
        Map<String, String> map = new HashMap<>();
        for (Map.Entry<String, Object> e : obj.entrySet()) {
            Object v = renderValue(e.getValue(), args);
            map.put(e.getKey(), v == null ? "" : String.valueOf(v));
        }
        return map;
    }

    /** 渲染模板值：支持 {{input.字段路径}}，常量与嵌套对象 */
    private Object renderValue(Object value, JSONObject args) {
        if (value instanceof String) {
            String s = (String) value;
            if (s.startsWith("{{") && s.endsWith("}}") && s.length() > 4) {
                String expr = s.substring(2, s.length() - 2).trim();
                if (expr.startsWith("input.")) {
                    return resolvePath(args, expr.substring(6));
                }
                return s;
            }
            return renderString(s, args);
        }
        if (value instanceof JSONObject) {
            JSONObject out = new JSONObject();
            for (Map.Entry<String, Object> e : ((JSONObject) value).entrySet()) {
                out.put(e.getKey(), renderValue(e.getValue(), args));
            }
            return out;
        }
        if (value instanceof JSONArray) {
            JSONArray out = new JSONArray();
            for (Object o : (JSONArray) value) {
                out.add(renderValue(o, args));
            }
            return out;
        }
        return value;
    }

    /** 字符串内嵌模板替换 */
    private String renderString(String s, JSONObject args) {
        // 单遍替换：替换进去的值不会被再次扫描，避免 {{input.x}} -> {{input.x}} 造成死循环
        java.util.regex.Matcher matcher = JAVA_TEMPLATE_PATTERN.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String expr = matcher.group(1).trim();
            Object v = null;
            if (expr.startsWith("input.")) {
                v = resolvePath(args, expr.substring(6));
            }
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** 点路径取值，如 input.user.id → args.user.id */
    private Object resolvePath(JSONObject args, String path) {
        if (args == null || !StringUtils.hasText(path)) {
            return null;
        }
        String[] parts = path.split("\\.");
        Object current = args;
        for (String p : parts) {
            if (current instanceof JSONObject) {
                current = ((JSONObject) current).get(p);
            } else {
                return null;
            }
        }
        return current;
    }

    /** 替换 URL 中的 {placeholder} */
    private String applyPathMapping(String url, Map<String, String> pathMap) {
        if (pathMap == null || pathMap.isEmpty()) {
            return url;
        }
        String result = url;
        for (Map.Entry<String, String> e : pathMap.entrySet()) {
            result = result.replace("{" + e.getKey() + "}", encode(e.getValue()));
        }
        return result;
    }

    /**
     * 拼接 URL 和查询参数，自动处理 ? 和 & 去重
     */
    private String buildUrlWithParams(String url, JSONObject args, Map<String, String> extraParams) {
        StringBuilder sb = new StringBuilder(url);
        boolean hasQuery = url.contains("?");

        if (args != null) {
            for (Map.Entry<String, Object> entry : args.entrySet()) {
                sb.append(hasQuery ? "&" : "?").append(entry.getKey()).append("=").append(encode(String.valueOf(entry.getValue())));
                hasQuery = true;
            }
        }
        if (extraParams != null) {
            for (Map.Entry<String, String> entry : extraParams.entrySet()) {
                sb.append(hasQuery ? "&" : "?").append(entry.getKey()).append("=").append(encode(entry.getValue()));
                hasQuery = true;
            }
        }
        return sb.toString();
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return value;
        }
    }

    /**
     * 剥掉 MCP 协议层加的 jsonArr 壳，取出下游接口真正期望的请求体
     * 仅用于 callExternalService 构建下游 body，不用于参数校验
     */
    private Object unwrapJsonArrArgs(JSONObject args) {
        if (args != null && args.containsKey(UNWRAP_KEY)) {
            return args.get(UNWRAP_KEY);
        }
        return args;
    }

    /**
     * 将 args 构建为 x-www-form-urlencoded 表单字符串
     */
    private String buildFormBody(JSONObject args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            if (sb.length() > 0) {
                sb.append("&");
            }
            sb.append(encode(entry.getKey())).append("=").append(encode(String.valueOf(entry.getValue())));
        }
        return sb.toString();
    }

    /**
     * 用 sk 换取 auth 信息 {header: {...}, params: {...}}
     */
    private JSONObject skExchange(String authUrl, String authorization) {
        if (!StringUtils.hasText(authUrl)) {
            throw new RuntimeException("未配置授权URL(auth_config.auth_url)");
        }
        urlSecurityValidator.validate(authUrl);

        // 从 Authorization header 提取 apikey（格式: Bearer {apikey}）
        String authHeader = authorization;
        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith("Bearer ")) {
            throw new RuntimeException("请求头 Authorization 格式错误，需为 Bearer {apikey}");
        }
        String apikey = authHeader.substring("Bearer ".length()).trim();
        if (!StringUtils.hasText(apikey)) {
            throw new RuntimeException("Authorization 中缺少 apikey");
        }

        try {
            JSONObject authBody = new JSONObject();
            authBody.put("sk", apikey);

            String response = webClient.post()
                    .uri(authUrl)
                    .header("Content-Type", "application/json")
                    .bodyValue(authBody.toJSONString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (!StringUtils.hasText(response)) {
                throw new RuntimeException("授权接口返回为空");
            }
            JSONObject respJson = JSON.parseObject(response);
            Integer code = respJson.getInteger("code");
            if (code == null || code != 0) {
                throw new RuntimeException("授权接口返回错误: " + respJson.getString("message"));
            }
            JSONObject resultObj = respJson.getJSONObject("result");
            if (resultObj == null) {
                throw new RuntimeException("授权接口返回缺少 result");
            }
            logger.info("授权接口返回 header: {}, params: {}",
                    resultObj.getJSONObject("header") != null ? resultObj.getJSONObject("header").keySet() : "null",
                    resultObj.getJSONObject("params") != null ? resultObj.getJSONObject("params").keySet() : "null");
            return resultObj;

        } catch (Exception e) {
            logger.error("授权请求失败, authUrl: {}", authUrl, e);
            throw new RuntimeException("授权请求失败: " + e.getMessage(), e);
        }
    }

    /**
     * 校验工具调用参数
     * 根据 mcp_tool.res_json 中的 inputSchema（标准 JSON Schema Draft-07），
     * 使用 networknt json-schema-validator 校验大模型传过来的 arguments 是否合法。
     */
    private void validateArguments(McpTool tool, Object arguments) {
        if (tool == null || !StringUtils.hasText(tool.getResJson())) {
            return;
        }

        JSONObject inputSchema = extractInputSchema(tool);
        if (inputSchema == null) {
            return;
        }

        JsonSchema jsonSchema = getCompiledSchema(tool, inputSchema);
        if (jsonSchema == null) {
            return;
        }

        JsonNode argumentsNode;
        try {
            argumentsNode = OBJECT_MAPPER.readTree(arguments == null ? "{}" : JSON.toJSONString(arguments));
        } catch (Exception e) {
            throw new IllegalArgumentException("参数校验失败 (" + tool.getCode() + "):\n参数不是合法的 JSON，请检查后重试。");
        }

        Set<ValidationMessage> messages = jsonSchema.validate(argumentsNode);
        if (messages == null || messages.isEmpty()) {
            return;
        }

        StringBuilder msg = new StringBuilder("参数校验失败 (").append(tool.getCode()).append("):\n")
                .append("请修改参数格式后重试。\n");
        for (ValidationMessage message : messages) {
            msg.append("- ").append(message.getMessage()).append("\n");
        }
        throw new IllegalArgumentException(msg.toString().trim());
    }

    /**
     * 从 mcp_tool.res_json 中提取 inputSchema
     */
    private JSONObject extractInputSchema(McpTool tool) {
        try {
            JSONObject toolJson = JSON.parseObject(tool.getResJson());
            if (toolJson == null) {
                return null;
            }
            return toolJson.getJSONObject("inputSchema");
        } catch (Exception e) {
            logger.warn("解析工具定义失败, code: {}, 跳过参数校验", tool.getCode(), e);
            return null;
        }
    }

    /**
     * 编译并缓存 JSON Schema（key = inputSchema 原文）
     */
    private JsonSchema getCompiledSchema(McpTool tool, JSONObject inputSchema) {
        String schemaJson = inputSchema.toJSONString();
        if (schemaCache.size() > SCHEMA_CACHE_MAX) {
            schemaCache.clear();
        }
        try {
            return schemaCache.computeIfAbsent(schemaJson, key -> {
                try {
                    JsonNode schemaNode = OBJECT_MAPPER.readTree(key);
                    return JSON_SCHEMA_FACTORY.getSchema(schemaNode);
                } catch (Exception e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
            });
        } catch (Exception e) {
            logger.warn("解析工具 Schema 失败, code: {}, 跳过参数校验: {}", tool.getCode(), e.getMessage());
            return null;
        }
    }

    /**
     * 构建 tools/list 响应：
     * <ul>
     *   <li>统一走 {@link ToolAccessPolicy}（与 tools/call 鉴权一致）；</li>
     *   <li>DIRECT：按 pageSize 游标分页返回全部可见工具；</li>
     *   <li>META：只返回热工具 + 3 个元工具；</li>
     *   <li>同时计算预算并触发超阈值告警。</li>
     * </ul>
     */




    /** 客户端白名单：逗号分隔的小写子串匹配；空配置表示不限制 */

    @Override
    public com.mcp.transfer.shared.dto.mcp.result.tools.McpTool toToolDto(McpServer server, McpTool tool) {
        try {
            com.mcp.transfer.shared.dto.mcp.result.tools.McpTool dto =
                    JSON.parseObject(tool.getResJson(), com.mcp.transfer.shared.dto.mcp.result.tools.McpTool.class);
            if (dto == null) {
                dto = new com.mcp.transfer.shared.dto.mcp.result.tools.McpTool();
            }
            dto.setName(tool.getCode());
            dto.setVersion(tool.getVersion());
            dto.setTags(parseTags(tool.getTags()));
            if (toolExposureProperties.isIncludeServerMeta()) {
                JSONObject serverMeta = new JSONObject();
                serverMeta.put("id", server.getId());
                serverMeta.put("name", server.getName());
                dto.setServer(serverMeta);
            }
            return toolSchemaTrimmer.trim(dto);
        } catch (Exception e) {
            logger.error("构建工具响应列表, server={}, tool={} 转换失败", server.getName(), tool.getCode(), e);
            return null;
        }
    }




    /**
     * Resource 列表：可见性策略 + 可选 cursor 分页 + 预算 meta。
     */

    /**
     * Resource 模板列表：与 resources/list 同一套可见性、分页与预算。
     */






    /**
     * prompts/get：优先返回 res_json 中预置的 messages；否则用描述/名称生成一条 user 消息。
     */

    @Override
    public JSONObject promptGetOnServer(McpServer server, String promptName, String appId) {
        if (server == null || !StringUtils.hasText(promptName)) {
            return null;
        }
        McpPrompt prompt = mcpPromptMapper.selectOneByServerIdAndName(server.getId(), promptName);
        if (prompt == null || !assetAccessPolicy.canUsePrompt(server, appId, prompt)) {
            return null;
        }
        JSONObject def = parseDefinition(prompt.getResJson());
        if (def != null && def.containsKey("messages")) {
            return assetContentLimiter.limitPrompt(def);
        }
        JSONObject result = new JSONObject();
        String description = def != null ? def.getString("description") : null;
        result.put("description", description == null ? prompt.getName() : description);
        JSONArray messages = new JSONArray();
        JSONObject message = new JSONObject();
        message.put("role", "user");
        JSONObject content = new JSONObject();
        content.put("type", "text");
        content.put("text", description == null ? prompt.getName() : description);
        message.put("content", content);
        messages.add(message);
        result.put("messages", messages);
        return result;
    }

    /**
     * resources/read：优先返回 res_json 中预置的 contents；否则把定义 JSON 作为文本返回。
     */

    @Override
    public JSONObject resourceReadOnServer(McpServer server, String uri, String appId) {
        if (server == null || !StringUtils.hasText(uri)) {
            return null;
        }
        McpResource resource = mcpResourceMapper.selectOneByServerIdAndUri(server.getId(), uri);
        if (resource == null || !assetAccessPolicy.canUseResource(server, appId, resource)) {
            return null;
        }
        JSONObject def = parseDefinition(resource.getResJson());
        if (def != null && def.containsKey("contents")) {
            return assetContentLimiter.limitResource(def);
        }
        JSONObject result = new JSONObject();
        JSONArray contents = new JSONArray();
        JSONObject item = new JSONObject();
        item.put("uri", resource.getUri());
        item.put("mimeType", "application/json");
        item.put("text", resource.getResJson() == null ? "" : resource.getResJson());
        contents.add(item);
        result.put("contents", contents);
        return assetContentLimiter.limitResource(result);
    }

    /** 解析逗号分隔的标签字符串为列表，空则返回 null */
    private List<String> parseTags(String tags) {
        if (!StringUtils.hasText(tags)) {
            return null;
        }
        List<String> list = new ArrayList<>();
        for (String t : tags.split(",")) {
            if (StringUtils.hasText(t)) {
                list.add(t.trim());
            }
        }
        return list.isEmpty() ? null : list;
    }

    /** 解析定义 JSON，失败返回 null */
    private JSONObject parseDefinition(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return JSON.parseObject(json);
        } catch (Exception e) {
            logger.warn("解析定义 JSON 失败: {}", json);
            return null;
        }
    }

    /**
     * 构建MCP初始化响应（按 server）。
     * protocolVersion/serverInfo 读 capabilities_json（缺失时兜底），
     * capabilities.tools/resources/prompts 按实际注册内容自动派生。
     */

}
