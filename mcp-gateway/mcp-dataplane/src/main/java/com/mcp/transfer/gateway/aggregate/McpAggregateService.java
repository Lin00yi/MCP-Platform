package com.mcp.transfer.gateway.aggregate;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mcp.transfer.config.session.McpSessionCache;
import com.mcp.transfer.config.session.SessionInfo;
import com.mcp.transfer.dto.mcp.req.McpRequest;
import com.mcp.transfer.dto.mcp.req.Params;
import com.mcp.transfer.dto.mcp.res.McpResponse;
import com.mcp.transfer.dto.mcp.result.data.McpResData;
import com.mcp.transfer.dto.mcp.result.tools.McpToolsListResult;
import com.mcp.transfer.entity.McpPrompt;
import com.mcp.transfer.entity.McpResource;
import com.mcp.transfer.entity.McpServer;
import com.mcp.transfer.entity.McpTool;
import com.mcp.transfer.exposure.AssetAccessPolicy;
import com.mcp.transfer.exposure.MetaToolFactory;
import com.mcp.transfer.exposure.TokenEstimator;
import com.mcp.transfer.exposure.ToolAccessPolicy;
import com.mcp.transfer.exposure.ToolBudgetAlerter;
import com.mcp.transfer.exposure.ToolDefinitionSupport;
import com.mcp.transfer.exposure.ToolExposureModes;
import com.mcp.transfer.exposure.ToolExposureProperties;
import com.mcp.transfer.exposure.ToolResultService;
import com.mcp.transfer.exposure.ToolSchemaCompactor;
import com.mcp.transfer.exposure.ToolSearchService;
import com.mcp.transfer.gateway.McpUpstreamClient;
import com.mcp.transfer.gateway.service.McpService;
import com.mcp.transfer.identity.McpIdentityContext;
import com.mcp.transfer.mapper.McpServerMapper;
import com.mcp.transfer.mapper.McpToolMapper;
import com.mcp.transfer.utils.ServerKeyUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 方案 C：网关级统一入口。
 *
 * <p>把“当前 Endpoint/Namespace 范围内启用的多个 Server”合并成一个 MCP 端点：
 * <ul>
 *   <li>工具名：{@code {serverKey}__{toolCode}}，调用时按前缀路由回原 Server；</li>
 *   <li>Prompt 名：同样加前缀；Resource 保留原 URI，按可见集合查找归属 Server；</li>
 *   <li>原生 Server 直接复用本地工具执行链路；MCP / AGGREGATE 代理 Server 通过
 *       {@link McpUpstreamClient} 懒建立下游会话后转发（当前统一入口聚合工具，代理型
 *       Server 的 Prompt/Resource 暂不聚合）。</li>
 * </ul>
 *
 * <p>仅由 Endpoint（{@code /mcp/e/{endpointKey}}）调用；调用前必须设置
 * {@link AggregateScope}，否则返回明确错误。
 */
@Service
public class McpAggregateService {

    private static final Logger logger = LoggerFactory.getLogger(McpAggregateService.class);

    private static final String AGGREGATE_NAME = "MCP Gateway 统一入口";
    private static final String AGGREGATE_VERSION = "1.0.0";

    /** 代理 Server 工具目录缓存：下游 tools/list 可能较慢，30s 内复用 */
    private static final long PROXY_TOOL_CACHE_MS = 30_000L;
    private static final int PROXY_CACHE_MAX = 1_000;
    /** get_tool_schema 批量上限 */
    private static final int MAX_SCHEMA_BATCH = 10;

    @Value("${mcp.public-base-url:http://127.0.0.1:8081}")
    private String publicBaseUrl;

    @Autowired
    private McpServerMapper mcpServerMapper;

    @Autowired
    private McpToolMapper mcpToolMapper;

    @Autowired
    private ToolAccessPolicy toolAccessPolicy;

    @Autowired
    private AssetAccessPolicy assetAccessPolicy;

    @Autowired
    private ToolExposureProperties toolExposureProperties;

    @Autowired
    private ToolSearchService toolSearchService;

    @Autowired
    private ToolBudgetAlerter toolBudgetAlerter;

    @Autowired
    private TokenEstimator tokenEstimator;

    @Autowired
    private ToolResultService toolResultService;

    @Autowired
    private McpService mcpService;

    @Autowired
    private McpUpstreamClient mcpUpstreamClient;

    @Autowired
    private McpSessionCache mcpSessionCache;

    private final Map<Integer, ProxyToolCache> proxyToolCache = new ConcurrentHashMap<>();

    // ==================== 模式 / 目录 ====================

    /** 可见 Server：全部启用 Server（应用授权已移除），再按 Endpoint/Namespace 范围收窄。 */
    public List<McpServer> visibleServers(String appId) {
        List<McpServer> all = mcpServerMapper.getAllByStatus(1);
        if (CollectionUtils.isEmpty(all)) {
            return Collections.emptyList();
        }
        List<McpServer> result = new ArrayList<>(all);
        AggregateScope.Scope scope = AggregateScope.get();
        if (scope != null) {
            result.removeIf(server -> server.getId() == null || !scope.getServerIds().contains(server.getId()));
        }
        result.sort(Comparator.comparing(McpServer::getId, Comparator.nullsLast(Integer::compareTo)));
        return result;
    }

    /** 对外前缀：Endpoint 模式用 Namespace 前缀，否则用 serverKey。 */
    private String exposedPrefix(McpServer server) {
        AggregateScope.Scope scope = AggregateScope.get();
        if (scope != null && server != null && server.getId() != null) {
            String prefix = scope.getPrefixByServer().get(server.getId());
            if (StringUtils.hasText(prefix)) {
                return prefix;
            }
        }
        return serverKey(server);
    }

    public String serverKey(McpServer server) {
        if (server == null) {
            return null;
        }
        if (ServerKeyUtils.isValid(server.getServerKey())) {
            return server.getServerKey();
        }
        return ServerKeyUtils.fallback(server.getName(), server.getId());
    }

    public Integer firstTenantId(String appId) {
        for (McpServer server : visibleServers(appId)) {
            if (server.getTenantId() != null) {
                return server.getTenantId();
            }
        }
        return null;
    }

    private long time(Date date) {
        return date == null ? 0L : date.getTime();
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0x0F, 16));
                sb.append(Character.forDigit(b & 0x0F, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算统一入口 revision 失败", e);
        }
    }

    private String absoluteUrl(String path) {
        String base = StringUtils.hasText(publicBaseUrl) ? publicBaseUrl.trim() : "http://127.0.0.1:8081";
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return path.startsWith("/") ? base + path : base + "/" + path;
    }

    // ==================== 请求处理 ====================

    public McpResponse handle(McpRequest request, HttpServletRequest httpRequest) {
        McpResponse response = new McpResponse();
        response.setId(request == null || request.getId() == null
                ? java.util.UUID.randomUUID().toString() : request.getId());
        if (request == null || !StringUtils.hasText(request.getMethod())) {
            response.setError(errorBody(-32600, "无效的 MCP 请求"));
            return response;
        }
        AggregateScope.Scope requestScope = AggregateScope.get();
        if (requestScope == null) {
            response.setError(errorBody(-32001, "请通过 Endpoint 访问：/mcp/e/{endpointKey}"));
            return response;
        }

        String method = request.getMethod();
        // 身份下传开启时，统一入口也必须覆盖客户端传入的客编字段
        mcpService.overrideCustomerArgs(request);
        String appId = attr(httpRequest, "mcpAppId");
        String sessionId = sessionId(httpRequest);
        try {
            switch (method) {
                case "initialize":
                case "mcp/initialize":
                    bindClientInfo(request, sessionId);
                    bindIdentity(sessionId);
                    response.setResult(buildInitializeResult(request));
                    break;
                case "ping":
                    response.setResult(new JSONObject());
                    break;
                case "notifications/initialized":
                    response.setResult(new JSONObject());
                    break;
                case "tools/list":
                    response.setResult(buildToolsList(appId, request, sessionId));
                    break;
                case "tools/call":
                    response.setResult(callTool(appId, request, httpRequest, sessionId));
                    break;
                case "prompts/list":
                    response.setResult(buildPromptsList(appId, request));
                    break;
                case "prompts/get":
                    response.setResult(getPrompt(appId, request));
                    break;
                case "resources/list":
                    response.setResult(buildResourcesList(appId, request, false));
                    break;
                case "resources/templates/list":
                    response.setResult(buildResourcesList(appId, request, true));
                    break;
                case "resources/read":
                    response.setResult(readResource(appId, request));
                    break;
                default:
                    if (method.startsWith("notifications/")) {
                        response.setResult(null);
                    } else {
                        response.setError(errorBody(-32601, "统一入口暂不支持方法: " + method));
                    }
            }
        } catch (Exception e) {
            logger.error("统一入口处理失败, method={}, appId={}", method, appId, e);
            response.setResult(errorResult("统一入口处理失败: " + e.getMessage()));
            response.setError(null);
        }

        setAggregateTenant(httpRequest, firstTenantId(appId));
        String toolName = "tools/call".equals(method) && request.getParams() != null
                ? request.getParams().getName() : null;
        mcpService.saveMcpRequestLog(method, toolName, requestScope.getPath(), httpRequest, request, response);
        return response;
    }

    private void bindIdentity(String sessionId) {
        if (StringUtils.hasText(sessionId)) {
            mcpSessionCache.bind(sessionId, McpIdentityContext.get());
        }
    }

    private void bindClientInfo(McpRequest request, String sessionId) {
        if (!StringUtils.hasText(sessionId) || request.getParams() == null
                || request.getParams().getClientInfo() == null) {
            return;
        }
        mcpSessionCache.bindClient(sessionId,
                request.getParams().getClientInfo().getName(),
                request.getParams().getClientInfo().getVersion());
    }

    private JSONObject buildInitializeResult(McpRequest request) {
        JSONObject result = new JSONObject();
        String protocolVersion = request.getParams() != null && StringUtils.hasText(request.getParams().getProtocolVersion())
                ? request.getParams().getProtocolVersion() : "2024-11-05";
        result.put("protocolVersion", protocolVersion);
        JSONObject serverInfo = new JSONObject();
        AggregateScope.Scope scope = AggregateScope.get();
        serverInfo.put("name", scope == null ? AGGREGATE_NAME : scope.getEndpointName());
        serverInfo.put("version", AGGREGATE_VERSION);
        result.put("serverInfo", serverInfo);

        JSONObject capabilities = new JSONObject();
        JSONObject tools = new JSONObject();
        tools.put("listChanged", true);
        capabilities.put("tools", tools);
        capabilities.put("prompts", new JSONObject());
        capabilities.put("resources", new JSONObject());
        result.put("capabilities", capabilities);
        return result;
    }

    /**
     * Endpoint 的 tools/list。
     *
     * <p>按成员 Server 各自的暴露模式合并：
     * <ul>
     *   <li>DIRECT Server：全量可见工具（带 namespace 前缀）；</li>
     *   <li>META Server：只出配置的常驻热工具（带前缀），不出全量；</li>
     *   <li>只要存在任一 META Server，就追加一套端点级元工具
     *       {@code search_tools / get_tool_schema / call_tool / read_result_chunk}（不带前缀，覆盖整个 Endpoint）；</li>
     *   <li>META 全量关闭（{@code mcp.tools.meta-enabled=false}）时全部按 DIRECT 处理。</li>
     * </ul>
     *
     * <p>顶层工具列表因此从 O(可见工具数) 降为
     * {@code DIRECT 工具 + 热工具 + 3}，并把 {@code visibleTokens/returnedTokens}
     * 等预算写入 {@code _meta}。
     */
    private McpToolsListResult buildToolsList(String appId, McpRequest request, String sessionId) {
        List<ServerCatalog> catalogs = catalogs(appId, sessionId);
        boolean metaActive = isMetaActive(catalogs);

        List<com.mcp.transfer.dto.mcp.result.tools.McpTool> all = new ArrayList<>();
        Set<String> names = new HashSet<>();
        int totalTools = 0;
        int visibleTools = 0;
        int hotTools = 0;
        int directServers = 0;
        int metaServers = 0;
        long visibleTokens = 0;

        for (ServerCatalog catalog : catalogs) {
            totalTools += catalog.totalTools;
            visibleTools += catalog.visibleTools();
            visibleTokens += catalog.visibleTokens();

            if (catalog.meta) {
                metaServers++;
                for (McpTool hot : toolAccessPolicy.hotTools(catalog.server, catalog.nativeTools)) {
                    if (addUnique(all, names, toExposedToolDto(catalog, hot))) {
                        hotTools++;
                    }
                }
            } else {
                directServers++;
                for (McpTool tool : catalog.nativeTools) {
                    addUnique(all, names, toExposedToolDto(catalog, tool));
                }
                for (JSONObject definition : catalog.proxyTools) {
                    addUnique(all, names, toExposedProxyDto(catalog, definition));
                }
            }

            if (!isProxy(catalog.server)) {
                toolBudgetAlerter.check(catalog.server,
                        toolAccessPolicy.budget(catalog.server, appId, false));
            }
        }

        if (metaActive) {
            for (com.mcp.transfer.dto.mcp.result.tools.McpTool metaTool : MetaToolFactory.build()) {
                addUnique(all, names, metaTool);
            }
        }
        // 顶层列表顺序必须稳定（Prompt Cache 友好），不依赖 DB 返回顺序
        all.sort(Comparator.comparing(
                com.mcp.transfer.dto.mcp.result.tools.McpTool::getName,
                Comparator.nullsLast(String::compareTo)));

        String cursor = request.getParams() == null ? null : request.getParams().getCursor();
        int min = Math.max(1, toolExposureProperties.getPaginationMinTools());
        String clientName = clientName(sessionId);
        boolean nowPaginate = all.size() > min || StringUtils.hasText(cursor);
        int pageSize = nowPaginate ? Math.max(1, toolExposureProperties.getPageSize())
                : Math.max(all.size(), 1);
        int offset = nowPaginate ? AggregateCursor.decode(cursor) : 0;
        int start = Math.min(offset, all.size());
        int end = Math.min(start + pageSize, all.size());

        List<com.mcp.transfer.dto.mcp.result.tools.McpTool> dtos =
                new ArrayList<>(all.subList(start, end));

        McpToolsListResult result = new McpToolsListResult();
        result.setTools(dtos);
        if (end < all.size()) {
            result.setNextCursor(AggregateCursor.encode(end));
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalTools", totalTools);
        meta.put("visibleTools", visibleTools);
        meta.put("returned", dtos.size());
        meta.put("accessMode", "AGGREGATE");
        meta.put("exposureMode", metaActive ? ToolExposureModes.EXPOSURE_META : ToolExposureModes.EXPOSURE_DIRECT);
        meta.put("directServers", directServers);
        meta.put("metaServers", metaServers);
        meta.put("hotTools", hotTools);
        meta.put("serverCount", catalogs.size());
        meta.put("clientName", clientName);
        meta.put("sessionBound", StringUtils.hasText(sessionId));
        meta.put("paginationUsed", nowPaginate);
        meta.put("paginationThreshold", min);
        meta.put("paginationRisk", all.size() > min && !clientAllowed(clientName));
        meta.put("visibleTokens", visibleTokens);
        meta.put("returnedTokens", returnedTokens(dtos));
        meta.put("tokenizer", tokenEstimator.method());
        if (StringUtils.hasText(cursor)) {
            meta.put("cursorUsed", true);
        }
        result.setMeta(meta);
        return result;
    }

    /**
     * 当前 Endpoint 的成员目录（每个 Server 一份，含暴露模式、可见工具、前缀）。
     * 代理型 Server 统一按 DIRECT 处理：无法在网关侧解析热工具 code。
     */
    private List<ServerCatalog> catalogs(String appId, String sessionId) {
        List<ServerCatalog> catalogs = new ArrayList<>();
        for (McpServer server : visibleServers(appId)) {
            String prefix = exposedPrefix(server);
            if (isProxy(server)) {
                List<JSONObject> definitions = proxyTools(server, sessionId);
                catalogs.add(new ServerCatalog(server, prefix, false,
                        Collections.emptyList(), definitions, definitions.size()));
                continue;
            }
            List<McpTool> visible = toolAccessPolicy.visibleTools(server, appId, false);
            int total = toolAccessPolicy.allTools(server.getId()).size();
            catalogs.add(new ServerCatalog(server, prefix, isMetaServer(server),
                    visible, Collections.emptyList(), total));
        }
        return catalogs;
    }

    private boolean isMetaServer(McpServer server) {
        return !isProxy(server)
                && toolExposureProperties.isMetaEnabled()
                && ToolExposureModes.EXPOSURE_META.equals(ToolExposureModes.exposureOf(server));
    }

    /** 轻量判断：只看成员 Server 的暴露模式，不加载工具目录（供 tools/call 快速分流）。 */
    private boolean hasMetaServer(String appId) {
        for (McpServer server : visibleServers(appId)) {
            if (isMetaServer(server)) {
                return true;
            }
        }
        return false;
    }

    private boolean isMetaActive(List<ServerCatalog> catalogs) {
        for (ServerCatalog catalog : catalogs) {
            if (catalog.meta) {
                return true;
            }
        }
        return false;
    }

    private com.mcp.transfer.dto.mcp.result.tools.McpTool toExposedToolDto(
            ServerCatalog catalog, McpTool tool) {
        if (tool == null) {
            return null;
        }
        com.mcp.transfer.dto.mcp.result.tools.McpTool dto =
                mcpService.toToolDto(catalog.server, tool);
        if (dto == null) {
            return null;
        }
        dto.setName(exposeToolName(catalog.server, tool.getCode()));
        dto.setServer(toolExposureProperties.isIncludeServerMeta()
                ? serverMeta(catalog.server) : null);
        return dto;
    }

    private com.mcp.transfer.dto.mcp.result.tools.McpTool toExposedProxyDto(
            ServerCatalog catalog, JSONObject definition) {
        if (definition == null || !StringUtils.hasText(definition.getString("name"))) {
            return null;
        }
        try {
            com.mcp.transfer.dto.mcp.result.tools.McpTool dto = JSON.parseObject(
                    definition.toJSONString(), com.mcp.transfer.dto.mcp.result.tools.McpTool.class);
            if (dto == null) {
                return null;
            }
            dto.setName(exposeToolName(catalog.server, definition.getString("name")));
            dto.setDescription(ToolDefinitionSupport.truncate(
                    dto.getDescription(), toolExposureProperties.getMaxDescriptionLength()));
            dto.setServer(toolExposureProperties.isIncludeServerMeta()
                    ? serverMeta(catalog.server) : null);
            return dto;
        } catch (Exception e) {
            logger.warn("统一入口解析代理工具定义失败: server={}, error={}",
                    catalog.server.getName(), e.getMessage());
            return null;
        }
    }

    /** 工具名按 namespace 前缀暴露，并在超过上限时截断 + 短哈希（见 AggregateNaming）。 */
    private String exposeToolName(McpServer server, String name) {
        return AggregateNaming.exposeTool(exposedPrefix(server), name,
                toolExposureProperties.getMaxExposedNameLength());
    }

    private boolean addUnique(List<com.mcp.transfer.dto.mcp.result.tools.McpTool> tools,
                              Set<String> names,
                              com.mcp.transfer.dto.mcp.result.tools.McpTool tool) {
        if (tool == null || !StringUtils.hasText(tool.getName())) {
            return false;
        }
        if (!names.add(tool.getName())) {
            logger.warn("统一入口出现重复工具名，已忽略后者: {}", tool.getName());
            return false;
        }
        tools.add(tool);
        return true;
    }

    private long returnedTokens(List<com.mcp.transfer.dto.mcp.result.tools.McpTool> tools) {
        long tokens = 0;
        for (com.mcp.transfer.dto.mcp.result.tools.McpTool tool : tools) {
            tokens += tokenEstimator.estimate(JSON.toJSONString(tool)) + 30L;
        }
        return tokens;
    }

    private Object callTool(String appId, McpRequest request, HttpServletRequest httpRequest, String sessionId) {
        if (request.getParams() == null || !StringUtils.hasText(request.getParams().getName())) {
            return errorResult("tools/call 缺少 params.name");
        }
        String exposedName = request.getParams().getName();
        // Endpoint 级元工具：仅当至少一个成员 Server 处于 META 模式时可用
        if (MetaToolFactory.isMetaTool(exposedName) && hasMetaServer(appId)) {
            return callEndpointMetaTool(appId, exposedName,
                    request.getParams().getArguments() == null
                            ? new JSONObject() : request.getParams().getArguments(),
                    httpRequest, sessionId);
        }
        return callExposedTool(appId, exposedName,
                request.getParams().getArguments(), httpRequest, sessionId);
    }

    /**
     * 统一执行入口：标准 tools/call 与元工具 call_tool 共用。
     * 负责二次可见性校验、命名空间解包与调用日志。
     */
    private Object callExposedTool(String appId, String exposedName, JSONObject callArgs,
                                   HttpServletRequest httpRequest, String sessionId) {
        Target target = resolveTarget(appId, exposedName, sessionId);
        if (target == null) {
            return errorResult("工具不存在或当前应用无权访问: " + exposedName);
        }
        setAggregateTenant(httpRequest, target.server.getTenantId());
        McpRequest sub = buildCall(target.originalName, callArgs);
        boolean endpointMeta = hasMetaServer(appId);

        if (target.proxy) {
            McpResponse response = mcpUpstreamClient.proxy(target.server, sub, sessionId);
            if (response == null) {
                return errorResult("代理 Server 无响应: " + serverKey(target.server));
            }
            if (response.getError() != null) {
                return errorResult("代理 Server 调用失败: " + JSON.toJSONString(response.getError()));
            }
            return toolResultService.present(appId, "endpoint", response.getResult(), endpointMeta);
        }

        if (!toolAccessPolicy.canUse(target.server, appId, target.tool)) {
            logger.warn("统一入口工具调用被拒绝（未授权）: server={}, appId={}, tool={}",
                    target.server.getName(), appId, target.originalName);
            return errorResult("无权调用该工具: " + exposedName);
        }
        if (httpRequest != null) {
            // Endpoint 级 META 生效时，DIRECT 成员的超长结果也可用 read_result_chunk 继续读
            httpRequest.setAttribute("mcpResultChunkable", endpointMeta);
        }
        try {
            Object result = mcpService.executeToolOnServer(target.server, target.tool, sub, httpRequest);
            return result instanceof McpResData ? result : textResult(result);
        } catch (IllegalArgumentException e) {
            // 参数校验失败：交给模型当轮自修正
            return errorResult(e.getMessage());
        }
    }

    // ==================== Endpoint 级元工具 ====================

    private Object callEndpointMetaTool(String appId, String name, JSONObject args,
                                        HttpServletRequest httpRequest, String sessionId) {
        if (MetaToolFactory.SEARCH_TOOLS.equals(name)) {
            return metaSearch(appId, args, sessionId);
        }
        if (MetaToolFactory.GET_TOOL_SCHEMA.equals(name)) {
            return metaGetSchema(appId, args, sessionId);
        }
        if (MetaToolFactory.CALL_TOOL.equals(name)) {
            return metaCall(appId, args, httpRequest, sessionId);
        }
        if (MetaToolFactory.READ_RESULT_CHUNK.equals(name)) {
            return toolResultService.readChunk(appId, args);
        }
        return errorResult("未知元工具: " + name);
    }

    /**
     * search_tools：跨 namespace 检索当前 Endpoint 的可见工具。
     * 返回暴露名（带前缀）、简短描述与 tags，不返回 inputSchema。
     */
    private Object metaSearch(String appId, JSONObject args, String sessionId) {
        List<ServerCatalog> catalogs = catalogs(appId, sessionId);
        List<ToolSearchService.ToolDoc> docs = searchDocs(catalogs);
        ToolSearchService.SearchRequest request =
                ToolSearchService.parseRequest(args, toolExposureProperties);

        ToolSearchService.Mode configured =
                ToolSearchService.modeOf(toolExposureProperties.getSearchMode());
        if (configured == ToolSearchService.Mode.CATALOG
                || (configured == ToolSearchService.Mode.AUTO && !request.hasQuery())) {
            return catalogResult(catalogs, docs, request);
        }
        if (configured != ToolSearchService.Mode.AUTO) {
            request.setMode(configured);
        }

        ToolSearchService.SearchResult result = toolSearchService.searchDocuments(docs, request);
        JSONArray items = new JSONArray();
        for (ToolSearchService.ScoredDoc scored : result.getHits()) {
            ToolSearchService.ToolDoc doc = scored.getDoc();
            JSONObject item = new JSONObject();
            item.put("name", doc.getExposedName());
            item.put("description", ToolDefinitionSupport.truncate(
                    doc.getDescription(), toolExposureProperties.getSearchDescriptionLength()));
            if (!doc.getTags().isEmpty()) {
                item.put("tags", doc.getTags());
            }
            item.put("server", doc.getDomain());
            items.add(item);
        }
        JSONObject payload = new JSONObject();
        payload.put("mode", result.getMode().name());
        payload.put("query", request.getQuery());
        payload.put("totalTools", docs.size());
        payload.put("returned", items.size());
        payload.put("tools", items);
        if (items.isEmpty()) {
            payload.put("hint", "没有匹配的工具。可以换更短的关键词、去掉 tags 过滤，"
                    + "或传 domain 查看某个业务分组。");
        }
        return textResult(payload.toJSONString());
    }

    /** 目录模式：无 domain 给分组概览，指定 domain 列出该分组工具摘要。 */
    private Object catalogResult(List<ServerCatalog> catalogs,
                                 List<ToolSearchService.ToolDoc> docs,
                                 ToolSearchService.SearchRequest request) {
        List<ToolSearchService.ToolDoc> filtered =
                new ArrayList<>(toolSearchService.filterDocuments(docs, request));
        filtered.sort(Comparator.comparing(ToolSearchService.ToolDoc::getExposedName,
                Comparator.nullsLast(String::compareTo)));
        JSONObject payload = new JSONObject();
        payload.put("mode", ToolSearchService.Mode.CATALOG.name());
        payload.put("totalTools", filtered.size());

        if (StringUtils.hasText(request.getDomain())) {
            int limit = Math.min(filtered.size(), 200);
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
            payload.put("domain", request.getDomain());
            payload.put("returned", items.size());
            payload.put("tools", items);
            if (filtered.size() > limit) {
                payload.put("hint", "该分组工具较多，可加 query 或 tags 缩小范围。");
            }
            return textResult(payload.toJSONString());
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Set<String>> servers = new LinkedHashMap<>();
        for (ToolSearchService.ToolDoc doc : filtered) {
            String domain = doc.getDomain() == null ? "" : doc.getDomain();
            counts.merge(domain, 1, Integer::sum);
        }
        for (ServerCatalog catalog : catalogs) {
            servers.computeIfAbsent(catalog.prefix, key -> new LinkedHashSet<>())
                    .add(catalog.server.getName());
        }
        JSONArray groups = new JSONArray();
        List<String> domains = new ArrayList<>(counts.keySet());
        Collections.sort(domains);
        for (String domain : domains) {
            JSONObject group = new JSONObject();
            group.put("domain", domain);
            group.put("serverCount", servers.getOrDefault(domain,
                    Collections.emptySet()).size());
            List<String> names = new ArrayList<>(servers.getOrDefault(domain,
                    Collections.emptySet()));
            if (!names.isEmpty()) {
                group.put("servers", names);
            }
            group.put("toolCount", counts.get(domain));
            groups.add(group);
        }
        payload.put("groups", groups);
        payload.put("hint", "传 domain 列出该分组的工具，或直接传 query 检索。");
        return textResult(payload.toJSONString());
    }

    /** 把 native / 代理工具统一成检索文档。 */
    private List<ToolSearchService.ToolDoc> searchDocs(List<ServerCatalog> catalogs) {
        List<ToolSearchService.ToolDoc> docs = new ArrayList<>();
        for (ServerCatalog catalog : catalogs) {
            for (McpTool tool : catalog.nativeTools) {
                docs.add(ToolSearchService.ToolDoc.of(tool,
                        exposeToolName(catalog.server, tool.getCode()),
                        catalog.prefix, catalog.server.getId()));
            }
            for (JSONObject definition : catalog.proxyTools) {
                McpTool view = proxyView(definition);
                if (view != null) {
                    docs.add(ToolSearchService.ToolDoc.of(view,
                            exposeToolName(catalog.server, view.getCode()),
                            catalog.prefix));
                }
            }
        }
        return docs;
    }

    /**
     * get_tool_schema：按暴露名取回 inputSchema（跨 namespace）。
     * 支持 tool_codes 批量与 detail_level（compact/full）。
     */
    private Object metaGetSchema(String appId, JSONObject args, String sessionId) {
        String detailLevel = "full".equalsIgnoreCase(args.getString("detail_level"))
                ? "full" : "compact";
        List<String> codes = schemaCodes(args);
        if (codes.isEmpty()) {
            return errorResult("get_tool_schema 需要 name 或 tool_codes 参数（search_tools 返回的工具名）");
        }
        boolean batch = args.get("tool_codes") != null;
        if (!batch) {
            JSONObject item = schemaItem(appId, codes.get(0), detailLevel, sessionId);
            if (item.containsKey("error")) {
                return errorResult(item.getString("error"));
            }
            item.put("detailLevel", detailLevel);
            return textResult(item.toJSONString());
        }

        JSONArray schemas = new JSONArray();
        int limit = Math.min(codes.size(), MAX_SCHEMA_BATCH);
        for (int i = 0; i < limit; i++) {
            schemas.add(schemaItem(appId, codes.get(i), detailLevel, sessionId));
        }
        JSONObject payload = new JSONObject();
        payload.put("detailLevel", detailLevel);
        payload.put("requested", codes.size());
        payload.put("returned", schemas.size());
        payload.put("schemas", schemas);
        return textResult(payload.toJSONString());
    }

    private JSONObject schemaItem(String appId, String exposedName, String detailLevel,
                                  String sessionId) {
        JSONObject item = new JSONObject();
        item.put("name", exposedName);
        Target target = resolveTarget(appId, exposedName, sessionId);
        if (target == null) {
            item.put("error", "工具不存在或当前应用无权访问: " + exposedName);
            return item;
        }
        item.put("server", exposedPrefix(target.server));
        String description;
        JSONObject raw;
        if (target.proxy) {
            JSONObject definition = findProxyDefinition(target.server, exposedName, sessionId);
            if (definition == null) {
                item.put("error", "工具不存在或当前应用无权访问: " + exposedName);
                return item;
            }
            description = definition.getString("description");
            raw = definition.getJSONObject("inputSchema");
        } else {
            if (!toolAccessPolicy.canUse(target.server, appId, target.tool)) {
                item.put("error", "工具不存在或当前应用无权访问: " + exposedName);
                return item;
            }
            description = ToolDefinitionSupport.description(target.tool);
            raw = ToolDefinitionSupport.inputSchema(target.tool);
        }
        if ("compact".equals(detailLevel)) {
            description = ToolDefinitionSupport.truncate(description, 200);
        }
        item.put("description", description);
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

    /** call_tool：按暴露名代理执行真实工具（复用可见性校验与调用日志）。 */
    private Object metaCall(String appId, JSONObject args, HttpServletRequest httpRequest, String sessionId) {
        String name = args.getString("name");
        if (!StringUtils.hasText(name)) {
            return errorResult("call_tool 需要 name 参数（目标工具名）");
        }
        if (MetaToolFactory.isMetaTool(name)) {
            return errorResult("call_tool 不能调用元工具本身，请直接调用 " + name);
        }
        String reason = args.getString("reason");
        if (StringUtils.hasText(reason)) {
            logger.info("call_tool reason, endpoint, tool={}, reason={}", name, reason);
        }
        Object nested = args.get("arguments");
        JSONObject callArgs = new JSONObject();
        if (nested != null) {
            if (!(nested instanceof Map)) {
                return errorResult("call_tool.arguments 必须是 JSON 对象");
            }
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) nested).entrySet()) {
                if (entry.getKey() != null) {
                    callArgs.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }
        return callExposedTool(appId, name, callArgs, httpRequest, sessionId);
    }

    /** 代理工具没有 mcp_tool 实体，构造只读视图供统一的检索使用。 */
    private McpTool proxyView(JSONObject definition) {
        if (definition == null) {
            return null;
        }
        String name = definition.getString("name");
        if (!StringUtils.hasText(name)) {
            return null;
        }
        McpTool view = new McpTool();
        view.setCode(name);
        view.setName(definition.getString("description"));
        List<String> tags = proxyTags(definition);
        view.setTags(tags.isEmpty() ? null : String.join(",", tags));
        view.setResJson(definition.toJSONString());
        return view;
    }

    private List<String> proxyTags(JSONObject definition) {
        List<String> tags = new ArrayList<>();
        JSONArray array = definition.getJSONArray("tags");
        if (array != null) {
            for (int i = 0; i < array.size(); i++) {
                Object value = array.get(i);
                if (value != null && StringUtils.hasText(String.valueOf(value))) {
                    tags.add(String.valueOf(value));
                }
            }
        }
        return tags;
    }

    private JSONObject buildPromptsList(String appId, McpRequest request) {
        List<PromptItem> items = new ArrayList<>();
        for (McpServer server : visibleServers(appId)) {
            if (isProxy(server)) {
                continue;
            }
            for (McpPrompt prompt : assetAccessPolicy.visiblePrompts(server, appId, false)) {
                JSONObject definition = parseDefinition(prompt.getResJson());
                if (definition == null) {
                    definition = new JSONObject();
                }
                definition.put("name", AggregateNaming.expose(exposedPrefix(server), prompt.getName()));
                if (!definition.containsKey("description") && StringUtils.hasText(prompt.getResJson())) {
                    definition.put("description", prompt.getResJson());
                }
                definition.put("_meta", serverMeta(server));
                items.add(new PromptItem(server, definition));
            }
        }
        items.sort(Comparator
                .comparing((PromptItem p) -> p.server.getId(), Comparator.nullsLast(Integer::compareTo))
                .thenComparing(p -> p.definition.getString("name")));

        String cursor = request.getParams() == null ? null : request.getParams().getCursor();
        int offset = AggregateCursor.decode(cursor);
        int pageSize = Math.max(1, toolExposureProperties.getPageSize());
        int start = Math.min(offset, items.size());
        int end = Math.min(start + pageSize, items.size());
        JSONArray arr = new JSONArray();
        for (int i = start; i < end; i++) {
            arr.add(items.get(i).definition);
        }
        JSONObject result = new JSONObject();
        result.put("prompts", arr);
        if (end < items.size()) {
            result.put("nextCursor", AggregateCursor.encode(end));
        }
        JSONObject meta = new JSONObject();
        meta.put("totalPrompts", items.size());
        meta.put("returned", arr.size());
        meta.put("accessMode", "AGGREGATE");
        result.put("_meta", meta);
        return result;
    }

    private JSONObject getPrompt(String appId, McpRequest request) {
        String exposedName = request.getParams() == null ? null : request.getParams().getName();
        ResolvedServer resolved = resolveServer(appId, exposedName);
        if (resolved == null || isProxy(resolved.server)) {
            return null;
        }
        return mcpService.promptGetOnServer(resolved.server, resolved.name, appId);
    }

    private JSONObject buildResourcesList(String appId, McpRequest request, boolean templates) {
        List<ResourceItem> items = new ArrayList<>();
        for (McpServer server : visibleServers(appId)) {
            if (isProxy(server)) {
                continue;
            }
            for (McpResource resource : assetAccessPolicy.visibleResources(server, appId, false)) {
                JSONObject definition = parseDefinition(resource.getResJson());
                if (definition == null) {
                    definition = new JSONObject();
                }
                definition.put("name", AggregateNaming.expose(exposedPrefix(server), resource.getName()));
                if (templates) {
                    if (!definition.containsKey("uriTemplate")) {
                        definition.put("uriTemplate", resource.getUri());
                    }
                } else if (!definition.containsKey("uri")) {
                    definition.put("uri", resource.getUri());
                }
                definition.put("_meta", serverMeta(server));
                items.add(new ResourceItem(server, resource, definition));
            }
        }
        items.sort(Comparator
                .comparing((ResourceItem r) -> r.server.getId(), Comparator.nullsLast(Integer::compareTo))
                .thenComparing(r -> r.resource.getUri(), Comparator.nullsLast(String::compareTo)));

        String cursor = request.getParams() == null ? null : request.getParams().getCursor();
        int offset = AggregateCursor.decode(cursor);
        int pageSize = Math.max(1, toolExposureProperties.getPageSize());
        int start = Math.min(offset, items.size());
        int end = Math.min(start + pageSize, items.size());
        JSONArray arr = new JSONArray();
        for (int i = start; i < end; i++) {
            arr.add(items.get(i).definition);
        }
        JSONObject result = new JSONObject();
        result.put(templates ? "resourceTemplates" : "resources", arr);
        if (end < items.size()) {
            result.put("nextCursor", AggregateCursor.encode(end));
        }
        JSONObject meta = new JSONObject();
        meta.put(templates ? "totalResourceTemplates" : "totalResources", items.size());
        meta.put("returned", arr.size());
        meta.put("accessMode", "AGGREGATE");
        result.put("_meta", meta);
        return result;
    }

    private JSONObject readResource(String appId, McpRequest request) {
        String uri = request.getParams() == null ? null : request.getParams().getUri();
        if (!StringUtils.hasText(uri)) {
            return null;
        }
        for (McpServer server : visibleServers(appId)) {
            if (isProxy(server)) {
                continue;
            }
            for (McpResource resource : assetAccessPolicy.visibleResources(server, appId, false)) {
                if (uri.equals(resource.getUri())) {
                    return mcpService.resourceReadOnServer(server, uri, appId);
                }
            }
        }
        return null;
    }

    // ==================== 路由解析（供服务与鉴权拦截器共用） ====================

    /** 解析工具名 → 目标 Server / 工具；无前缀或无权限返回 null。 */
    public Target resolveTarget(String appId, String exposedName) {
        return resolveTarget(appId, exposedName, null);
    }

    /**
     * 解析暴露名 → 目标。
     *
     * <p>超长工具名会被截断并追加短哈希（见 {@link AggregateNaming}），
     * 因此按前缀解析出的 {@code resolved.name} 不一定是真实 code：
     * 先按 code 精确查，查不到再在可见工具里按“暴露名等于入参”反查。
     */
    public Target resolveTarget(String appId, String exposedName, String sessionId) {
        ResolvedServer resolved = resolveServer(appId, exposedName);
        if (resolved == null) {
            return null;
        }
        McpServer server = resolved.server;
        if (isProxy(server)) {
            JSONObject definition = findProxyDefinition(server, exposedName, sessionId);
            if (definition == null) {
                return null;
            }
            return new Target(server, null, definition.getString("name"), true);
        }
        McpTool tool = mcpToolMapper.selectOneByServerIdAndCode(server.getId(), resolved.name);
        if (tool == null) {
            tool = findByExposedName(appId, server, exposedName);
        }
        if (tool == null) {
            return null;
        }
        return new Target(server, tool, tool.getCode(), false);
    }

    /** 超长名/别名场景：在可见工具中按暴露名反查真实工具。 */
    private McpTool findByExposedName(String appId, McpServer server, String exposedName) {
        for (McpTool tool : toolAccessPolicy.visibleTools(server, appId, false)) {
            if (StringUtils.hasText(tool.getCode())
                    && exposeToolName(server, tool.getCode()).equals(exposedName)) {
                return tool;
            }
        }
        return null;
    }

    /** 代理 Server 的超长名/别名场景：在缓存的 tools/list 中按暴露名反查。 */
    private JSONObject findProxyDefinition(McpServer server, String exposedName, String sessionId) {
        for (JSONObject definition : proxyTools(server, sessionId)) {
            String name = definition.getString("name");
            if (StringUtils.hasText(name) && exposeToolName(server, name).equals(exposedName)) {
                return definition;
            }
        }
        return null;
    }

    /** 只解析 serverKey 前缀，不校验资产是否存在。 */
    public ResolvedServer resolveServer(String appId, String exposedName) {
        if (!StringUtils.hasText(exposedName)) {
            return null;
        }
        List<McpServer> servers = visibleServers(appId);
        Map<String, McpServer> byKey = new HashMap<>();
        for (McpServer server : servers) {
            byKey.put(exposedPrefix(server), server);
        }
        AggregateNaming.Resolved resolved = AggregateNaming.resolve(exposedName, byKey.keySet());
        if (resolved == null) {
            return null;
        }
        McpServer server = byKey.get(resolved.getServerKey());
        return server == null ? null : new ResolvedServer(server, resolved.getName());
    }

    public boolean isProxy(McpServer server) {
        if (server == null || server.getUpstreamType() == null) {
            return false;
        }
        return "MCP".equalsIgnoreCase(server.getUpstreamType())
                || "AGGREGATE".equalsIgnoreCase(server.getUpstreamType());
    }

    // ==================== 代理下游 ====================

    private List<JSONObject> proxyTools(McpServer server, String sessionId) {
        ProxyToolCache cached = proxyToolCache.get(server.getId());
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt > now) {
            return cached.tools;
        }
        List<JSONObject> tools = new ArrayList<>();
        try {
            McpRequest listRequest = new McpRequest();
            listRequest.setId(1);
            listRequest.setMethod("tools/list");
            McpResponse response = mcpUpstreamClient.proxy(server, listRequest, sessionId);
            Object resultObj = response == null ? null : response.getResult();
            if (resultObj != null) {
                JSONObject result = (JSONObject) JSON.toJSON(resultObj);
                JSONArray array = result.getJSONArray("tools");
                if (array != null) {
                    for (int i = 0; i < array.size(); i++) {
                        JSONObject definition = array.getJSONObject(i);
                        if (definition != null && StringUtils.hasText(definition.getString("name"))) {
                            tools.add(definition);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("统一入口拉取代理 Server 工具失败: server={}, error={}", server.getName(), e.getMessage());
        }
        if (proxyToolCache.size() > PROXY_CACHE_MAX) {
            proxyToolCache.clear();
        }
        proxyToolCache.put(server.getId(), new ProxyToolCache(tools, now + PROXY_TOOL_CACHE_MS));
        return tools;
    }

    // ==================== 小工具 ====================

    public void setAggregateTenant(HttpServletRequest request, Integer tenantId) {
        if (tenantId != null && request.getAttribute("mcpAggregateTenantId") == null) {
            request.setAttribute("mcpAggregateTenantId", tenantId);
        }
    }

    private String attr(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        return value == null ? null : value.toString();
    }

    private String sessionId(HttpServletRequest request) {
        String sid = request.getHeader("Mcp-Session-Id");
        if (StringUtils.hasText(sid)) {
            return sid;
        }
        return attr(request, "Mcp-Session-Id");
    }

    private String clientName(String sessionId) {
        if (StringUtils.hasText(sessionId)) {
            SessionInfo info = mcpSessionCache.getSession(sessionId);
            if (info != null && StringUtils.hasText(info.getClientName())) {
                return info.getClientName();
            }
        }
        return null;
    }

    private boolean clientAllowed(String clientName) {
        String config = toolExposureProperties.getPaginationClients();
        if (!StringUtils.hasText(config)) {
            return true;
        }
        String name = clientName == null ? "" : clientName.toLowerCase();
        for (String item : config.split(",")) {
            String token = item.trim().toLowerCase();
            if (!token.isEmpty() && name.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /** 构造发往下游/本地执行的 tools/call 请求（元工具代理与标准调用共用）。 */
    private McpRequest buildCall(String originalName, JSONObject arguments) {
        McpRequest sub = new McpRequest();
        sub.setJsonrpc("2.0");
        // 转发给上游时 id 不能为空（JSON-RPC 要求）；响应 id 由外层 handle 统一回填
        sub.setId(1);
        sub.setMethod("tools/call");
        Params params = new Params();
        params.setName(originalName);
        params.setArguments(arguments == null ? new JSONObject() : arguments);
        sub.setParams(params);
        return sub;
    }

    private JSONObject parseDefinition(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return JSON.parseObject(json);
        } catch (Exception e) {
            return null;
        }
    }

    private JSONObject serverMeta(McpServer server) {
        JSONObject meta = new JSONObject();
        meta.put("id", server.getId());
        meta.put("name", server.getName());
        meta.put("serverKey", serverKey(server));
        meta.put("path", server.getPath());
        return meta;
    }

    private JSONObject errorBody(int code, String message) {
        JSONObject error = new JSONObject();
        error.put("code", code);
        error.put("message", message);
        return error;
    }

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

    private McpResData errorResult(String message) {
        McpResData resData = textResult(message);
        resData.setIsError(true);
        return resData;
    }

    // ==================== 内部类型 ====================

    public static final class ResolvedServer {
        private final McpServer server;
        private final String name;

        public ResolvedServer(McpServer server, String name) {
            this.server = server;
            this.name = name;
        }

        public McpServer getServer() {
            return server;
        }

        public String getName() {
            return name;
        }
    }

    public static final class Target {
        public final McpServer server;
        public final McpTool tool;
        public final String originalName;
        public final boolean proxy;

        public Target(McpServer server, McpTool tool, String originalName, boolean proxy) {
            this.server = server;
            this.tool = tool;
            this.originalName = originalName;
            this.proxy = proxy;
        }
    }

    /** 一次请求内的 Server 目录：暴露模式 + 可见工具 + 前缀。 */
    private final class ServerCatalog {
        private final McpServer server;
        private final String prefix;
        private final boolean meta;
        private final List<McpTool> nativeTools;
        private final List<JSONObject> proxyTools;
        private final int totalTools;

        private ServerCatalog(McpServer server, String prefix, boolean meta,
                              List<McpTool> nativeTools, List<JSONObject> proxyTools, int totalTools) {
            this.server = server;
            this.prefix = prefix;
            this.meta = meta;
            this.nativeTools = nativeTools;
            this.proxyTools = proxyTools;
            this.totalTools = totalTools;
        }

        private int visibleTools() {
            return nativeTools.size() + proxyTools.size();
        }

        private long visibleTokens() {
            long tokens = 0;
            for (McpTool tool : nativeTools) {
                if (tool.getTokenEstimate() != null && tool.getTokenEstimate() > 0) {
                    tokens += tool.getTokenEstimate();
                } else {
                    tokens += tokenEstimator.estimate(tool.getResJson()) + 30L;
                }
            }
            for (JSONObject definition : proxyTools) {
                tokens += tokenEstimator.estimate(definition.toJSONString()) + 30L;
            }
            return tokens;
        }
    }

    private static final class PromptItem {
        private final McpServer server;
        private final JSONObject definition;

        private PromptItem(McpServer server, JSONObject definition) {
            this.server = server;
            this.definition = definition;
        }
    }

    private static final class ResourceItem {
        private final McpServer server;
        private final McpResource resource;
        private final JSONObject definition;

        private ResourceItem(McpServer server, McpResource resource, JSONObject definition) {
            this.server = server;
            this.resource = resource;
            this.definition = definition;
        }
    }

    private static final class ProxyToolCache {
        private final List<JSONObject> tools;
        private final long expiresAt;

        private ProxyToolCache(List<JSONObject> tools, long expiresAt) {
            this.tools = tools;
            this.expiresAt = expiresAt;
        }
    }
}
