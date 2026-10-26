package com.mcp.transfer.gateway;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mcp.transfer.shared.config.security.UrlSecurityValidator;
import com.mcp.transfer.shared.dto.mcp.req.McpRequest;
import com.mcp.transfer.shared.dto.mcp.res.McpResponse;
import com.mcp.transfer.shared.entity.McpServer;
import com.mcp.transfer.gateway.session.UpstreamSessionStore;
import com.mcp.transfer.health.UpstreamCircuitBreaker;
import com.mcp.transfer.identity.IdentityAssertionResolver;
import com.mcp.transfer.identity.IdentityProperties;
import com.mcp.transfer.identity.IdentityRequiredException;
import com.mcp.transfer.identity.McpIdentityContext;
import com.mcp.transfer.identity.ResolvedIdentity;
import com.mcp.transfer.upstream.UpstreamDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * MCP 上游客户端（数据面第二跳）：作为 MCP client 连接下游真实 MCP server（Streamable HTTP）。
 *
 * <p>支持两种上游类型：
 * <ul>
 *   <li><b>MCP</b>（透明代理）：1 网关端点 → 1 下游 MCP server，整包透传；</li>
 *   <li><b>AGGREGATE</b>（聚合代理）：1 网关端点 → N 个下游 MCP server，
 *       工具/prompt/resource 名加 {@code {upstreamName}__} 命名空间前缀后合并，调用按前缀路由。</li>
 * </ul>
 *
 * <p>会话映射带 TTL 与容量上限，避免长期运行内存泄漏；所有上游调用带超时。
 */
@Component
public class McpUpstreamClient {

    private static final Logger logger = LoggerFactory.getLogger(McpUpstreamClient.class);

    private static final String MCP_SESSION_ID_HEADER = "Mcp-Session-Id";

    /** 上游会话映射 TTL：30 分钟（与网关 session 一致） */
    private static final long SESSION_TTL_MS = 30 * 60 * 1000L;

    /** 单上游在会话映射里的固定 field 名 */
    private static final String SINGLE_UPSTREAM_KEY = "__single__";

    private static final Duration UPSTREAM_TIMEOUT = Duration.ofSeconds(30);

    /** 聚合列表的统一总超时：超过后放弃未完成的上游，用已完成结果继续合并 */
    private static final long AGGREGATE_LIST_TIMEOUT_MS = 5000L;

    /** 聚合列表并行拉取线程池（守护线程，固定 8；上游通常 < 10 个） */
    private final ExecutorService aggregateExecutor = Executors.newFixedThreadPool(8, r -> {
        Thread thread = new Thread(r, "mcp-aggregate-list");
        thread.setDaemon(true);
        return thread;
    });

    /** ensureSession 单飞锁：key = 网关会话 + 上游名，避免并发首包重复 initialize */
    private final Map<String, Object> sessionInitLocks = new ConcurrentHashMap<>();

    /** 上游会话映射：单副本走内存，多副本走 Redis */
    @Autowired
    private UpstreamSessionStore upstreamSessions;

    private final WebClient webClient;

    @Autowired
    private UrlSecurityValidator urlSecurityValidator;

    @Autowired(required = false)
    private IdentityProperties identityProperties;

    @Autowired
    private IdentityAssertionResolver identityAssertionResolver;

    @Autowired
    private UpstreamCircuitBreaker circuitBreaker;

    public McpUpstreamClient() {
        this.webClient = WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(20 * 1024 * 1024))
                .build();
    }

    /**
     * 代理一个 MCP 请求到下游真实 MCP server。
     */
    public McpResponse proxy(McpServer server, McpRequest request, String gatewaySessionId) {
        if (server.getId() != null && !circuitBreaker.allow(server.getId())) {
            logger.warn("[CIRCUIT] 上游调用被短路: server={}, method={}",
                    server.getName(), request.getMethod());
            return errorResponse(request, circuitBreaker.reason(server.getId()));
        }
        String type = server.getUpstreamType();
        String method = request.getMethod();
        try {
            McpResponse response = proxyInternal(server, request, gatewaySessionId, type, method);
            circuitBreaker.recordSuccess(server.getId());
            return response;
        } catch (IdentityRequiredException e) {
            circuitBreaker.recordFailure(server.getId());
            logger.warn("MCP 上游身份断言失败, server={}, method={}: {}",
                    server.getName(), method, e.getMessage());
            return errorResponse(request, -32003, e.getMessage());
        } catch (Exception e) {
            circuitBreaker.recordFailure(server.getId());
            logger.error("MCP 上游代理失败, server={}, type={}, method={}", server.getName(), type, method, e);
            return errorResponse(request, "上游 MCP server 调用失败");
        }
    }

    private McpResponse proxyInternal(McpServer server, McpRequest request, String gatewaySessionId,
                                      String type, String method) {
        String identityJwt = identityAssertionResolver.resolve(server);
        if ("AGGREGATE".equalsIgnoreCase(type)) {
            return aggregate(server, request, gatewaySessionId);
        }
        // 单代理
        String url = server.getUpstreamUrl();
        urlSecurityValidator.validate(url);
        if ("initialize".equals(method)) {
            return doInitialize(url, request, gatewaySessionId, identityJwt);
        }
        String upstreamSessionId = getSingleSession(gatewaySessionId);
        if (!StringUtils.hasText(upstreamSessionId)) {
            // 统一入口等场景可能没有显式 initialize 过该下游；惰性握手后再转发
            upstreamSessionId = ensureSingleSession(url, gatewaySessionId, identityJwt);
        }
        return doForward(url, request, upstreamSessionId, identityJwt);
    }

    // ============ 单代理（透明透传） ============

    private McpResponse doInitialize(String url, McpRequest request, String gatewaySessionId, String identityJwt) {
        WebClient.RequestBodySpec spec = webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON);
        applyUpstreamAuth(spec, identityJwt);
        ResponseEntity<String> resp = spec
                .bodyValue(JSON.toJSONString(request))
                .retrieve()
                .toEntity(String.class)
                .block(UPSTREAM_TIMEOUT);

        String upstreamSessionId = resp != null ? resp.getHeaders().getFirst(MCP_SESSION_ID_HEADER) : null;
        if (StringUtils.hasText(upstreamSessionId) && gatewaySessionId != null) {
            putSingleSession(gatewaySessionId, upstreamSessionId);
            logger.info("建立 MCP 上游会话映射: gatewaySession={} -> upstreamSession={}", gatewaySessionId, upstreamSessionId);
        }
        return parseResponse(resp != null ? resp.getBody() : null, request);
    }

    private McpResponse doForward(String url, McpRequest request, String upstreamSessionId, String identityJwt) {
        JSONObject raw = forwardRaw(url, upstreamSessionId, (JSONObject) JSON.toJSON(request), identityJwt);
        return wrap(request, raw);
    }

    /**
     * 惰性初始化单代理下游并返回会话 id。
     * 统一入口的 tools/list 可能先于任何显式 initialize 到达，不能直接带 null session 转发。
     */
    private String ensureSingleSession(String url, String gatewaySessionId, String identityJwt) {
        JSONObject init = new JSONObject();
        init.put("jsonrpc", "2.0");
        init.put("id", 1);
        init.put("method", "initialize");
        JSONObject params = new JSONObject();
        params.put("protocolVersion", "2024-11-05");
        params.put("capabilities", new JSONObject());
        JSONObject clientInfo = new JSONObject();
        clientInfo.put("name", "mcp-gateway");
        clientInfo.put("version", "1.0.0");
        params.put("clientInfo", clientInfo);
        init.put("params", params);

        try {
            WebClient.RequestBodySpec spec = webClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON);
            applyUpstreamAuth(spec, identityJwt);
            ResponseEntity<String> resp = spec
                    .bodyValue(init.toJSONString())
                    .retrieve()
                    .toEntity(String.class)
                    .block(UPSTREAM_TIMEOUT);
            String upstreamSessionId = resp != null ? resp.getHeaders().getFirst(MCP_SESSION_ID_HEADER) : null;
            if (StringUtils.hasText(upstreamSessionId) && gatewaySessionId != null) {
                putSingleSession(gatewaySessionId, upstreamSessionId);
                logger.info("建立 MCP 上游会话映射(惰性): gatewaySession={} -> upstreamSession={}",
                        gatewaySessionId, upstreamSessionId);
            }
            return upstreamSessionId;
        } catch (Exception e) {
            logger.warn("下游 MCP server 惰性初始化失败: url={}, error={}", url, e.getMessage());
            return null;
        }
    }

    // ============ 聚合代理（多下游合并） ============

    private McpResponse aggregate(McpServer server, McpRequest request, String gatewaySessionId) {
        List<UpstreamDef> upstreams = UpstreamDef.parseList(server.getUpstreamUrl());
        if (upstreams.isEmpty()) {
            return errorResponse(request, "聚合 server 未配置上游 MCP 地址");
        }
        for (UpstreamDef u : upstreams) {
            urlSecurityValidator.validate(u.getUrl());
        }

        // 每个上游可以有自己的 audience / 公钥 / 模式；未配置时回退聚合 Server
        Function<UpstreamDef, String> auth = u -> identityAssertionResolver.resolve(
                server, u.getIdentityMode(), u.getAudience(), u.getIdentityPublicKey());

        String method = request.getMethod();
        switch (method == null ? "" : method) {
            case "initialize":
                return aggregateInitialize(server, request, gatewaySessionId, upstreams, auth);
            case "tools/list":
                return aggregateList(request, gatewaySessionId, upstreams, "tools", "tools/list", auth);
            case "tools/call":
                return aggregateToolsCall(request, gatewaySessionId, upstreams, auth);
            case "prompts/list":
                return aggregateList(request, gatewaySessionId, upstreams, "prompts", "prompts/list", auth);
            case "prompts/get":
                return aggregateSingle(request, gatewaySessionId, upstreams, "prompts/get", auth);
            case "resources/list":
                return aggregateList(request, gatewaySessionId, upstreams, "resources", "resources/list", auth);
            case "resources/templates/list":
                return aggregateList(request, gatewaySessionId, upstreams, "resourceTemplates", "resources/templates/list", auth);
            case "resources/read":
                return aggregateSingle(request, gatewaySessionId, upstreams, "resources/read", auth);
            case "ping":
                return ok(request, new JSONObject());
            case "notifications/initialized":
                return ok(request, null);
            default:
                // 其它方法透传给第一个上游
                UpstreamDef first = upstreams.get(0);
                String token = auth.apply(first);
                String sid = ensureSession(gatewaySessionId, first, token);
                return wrap(request, forwardRaw(first.getUrl(), sid, (JSONObject) JSON.toJSON(request), token));
        }
    }

    /**
     * 聚合模式下 prompt/resource 的单项读取：按 name/uri 找到声明该资产的上游并透传。
     * 找不到时回退第一个上游。
     */
    private McpResponse aggregateSingle(McpRequest request, String gatewaySessionId,
                                        List<UpstreamDef> upstreams, String method,
                                        Function<UpstreamDef, String> auth) {
        UpstreamDef target = upstreams.get(0);
        String fullName = request.getParams() == null ? null : request.getParams().getName();
        String uri = request.getParams() == null ? null : request.getParams().getUri();
        String key = StringUtils.hasText(fullName) ? fullName : uri;
        if (StringUtils.hasText(key) && key.contains("__")) {
            String prefix = key.substring(0, key.indexOf("__"));
            for (UpstreamDef u : upstreams) {
                if (prefix.equals(u.getName())) {
                    target = u;
                    break;
                }
            }
            JSONObject req = (JSONObject) JSON.toJSON(request);
            JSONObject params = req.getJSONObject("params");
            if (params != null) {
                if (StringUtils.hasText(fullName)) {
                    params.put("name", fullName.substring(fullName.indexOf("__") + 2));
                }
                if (StringUtils.hasText(uri) && uri.contains("__")) {
                    params.put("uri", uri.substring(uri.indexOf("__") + 2));
                }
            }
            String token = auth.apply(target);
            String sid = ensureSession(gatewaySessionId, target, token);
            return wrap(request, forwardRaw(target.getUrl(), sid, req, token));
        }
        String token = auth.apply(target);
        String sid = ensureSession(gatewaySessionId, target, token);
        return wrap(request, forwardRaw(target.getUrl(), sid, (JSONObject) JSON.toJSON(request), token));
    }

    private McpResponse aggregateInitialize(McpServer server, McpRequest request, String gatewaySessionId,
                                            List<UpstreamDef> upstreams, Function<UpstreamDef, String> auth) {
        boolean hasTools = false, hasPrompts = false, hasResources = false;
        for (UpstreamDef u : upstreams) {
            String token = auth.apply(u);
            String sid = ensureSession(gatewaySessionId, u, token);
            JSONObject init = new JSONObject();
            init.put("jsonrpc", "2.0");
            init.put("id", request.getId());
            init.put("method", "initialize");
            JSONObject params = new JSONObject();
            params.put("protocolVersion", "2024-11-05");
            params.put("capabilities", new JSONObject());
            JSONObject clientInfo = new JSONObject();
            clientInfo.put("name", "mcp-gateway");
            clientInfo.put("version", "1.0.0");
            params.put("clientInfo", clientInfo);
            init.put("params", params);
            JSONObject initResp = forwardRaw(u.getUrl(), sid, init, token);
            JSONObject caps = initResp.getJSONObject("result") != null ? initResp.getJSONObject("result").getJSONObject("capabilities") : null;
            if (caps != null) {
                if (caps.getJSONObject("tools") != null) hasTools = true;
                if (caps.getJSONObject("prompts") != null) hasPrompts = true;
                if (caps.getJSONObject("resources") != null) hasResources = true;
            }
        }

        JSONObject result = new JSONObject();
        result.put("protocolVersion", "2024-11-05");
        JSONObject serverInfo = new JSONObject();
        serverInfo.put("name", server.getName());
        serverInfo.put("version", "1.0.0");
        result.put("serverInfo", serverInfo);
        JSONObject capabilities = new JSONObject();
        if (hasTools) {
            JSONObject tools = new JSONObject();
            tools.put("listChanged", true);
            capabilities.put("tools", tools);
        }
        if (hasPrompts) capabilities.put("prompts", new JSONObject());
        if (hasResources) capabilities.put("resources", new JSONObject());
        result.put("capabilities", capabilities);
        return ok(request, result);
    }

    /**
     * 聚合列表：各上游并行拉取（原先串行，N 个上游时延 = Σ RTT）。
     *
     * <p>身份断言（{@code auth.apply}）必须在请求线程解析（复用 ThreadLocal 身份）；
     * worker 线程里临时恢复身份上下文，保证「用户 token 透传」等行为不变。
     * 总超时 5s，超时/失败的上游直接跳过，不拖垮整次 tools/list。
     */
    private McpResponse aggregateList(McpRequest request, String gatewaySessionId, List<UpstreamDef> upstreams,
                                      String resultKey, String upstreamMethod,
                                      Function<UpstreamDef, String> auth) {
        ResolvedIdentity identity = McpIdentityContext.get();
        String userToken = McpIdentityContext.getUserToken();
        List<CompletableFuture<List<JSONObject>>> futures = new ArrayList<>(upstreams.size());
        for (UpstreamDef u : upstreams) {
            // 身份断言在这里解析：依赖请求线程的 ThreadLocal 身份
            final String token = auth.apply(u);
            futures.add(CompletableFuture.supplyAsync(
                    () -> listUpstreamItems(gatewaySessionId, u, request.getId(), upstreamMethod, resultKey,
                            token, identity, userToken),
                    aggregateExecutor));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(AGGREGATE_LIST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            logger.warn("[AGGREGATE] 上游列表拉取超时（{}ms），使用已完成的部分结果：{}",
                    AGGREGATE_LIST_TIMEOUT_MS, upstreams.size());
            futures.forEach(f -> f.cancel(true));
        } catch (Exception e) {
            logger.warn("[AGGREGATE] 上游列表拉取失败，使用已完成的部分结果: {}", e.getMessage());
        }

        JSONArray merged = new JSONArray();
        for (int i = 0; i < futures.size(); i++) {
            CompletableFuture<List<JSONObject>> future = futures.get(i);
            if (!future.isDone() || future.isCompletedExceptionally() || future.isCancelled()) {
                continue;
            }
            List<JSONObject> items;
            try {
                items = future.join();
            } catch (Exception e) {
                continue;
            }
            if (items == null) {
                continue;
            }
            String upstreamName = upstreams.get(i).getName();
            for (JSONObject item : items) {
                if (StringUtils.hasText(item.getString("name"))) {
                    item.put("name", upstreamName + "__" + item.getString("name"));
                }
                if (StringUtils.hasText(item.getString("uri"))) {
                    item.put("uri", upstreamName + "__" + item.getString("uri"));
                }
                merged.add(item);
            }
        }
        JSONObject result = new JSONObject();
        result.put(resultKey, merged);
        return ok(request, result);
    }

    /** 单个上游的列表拉取（在聚合线程池执行，需恢复身份上下文） */
    private List<JSONObject> listUpstreamItems(String gatewaySessionId, UpstreamDef upstream, Object requestId,
                                               String upstreamMethod, String resultKey, String token,
                                               ResolvedIdentity identity, String userToken) {
        McpIdentityContext.set(identity);
        McpIdentityContext.setUserToken(userToken);
        try {
            String sid = ensureSession(gatewaySessionId, upstream, token);
            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0");
            req.put("id", requestId);
            req.put("method", upstreamMethod);
            JSONObject resp = forwardRaw(upstream.getUrl(), sid, req, token);
            JSONArray items = resp.getJSONObject("result") != null
                    ? resp.getJSONObject("result").getJSONArray(resultKey) : null;
            if (items == null || items.isEmpty()) {
                return java.util.Collections.emptyList();
            }
            List<JSONObject> list = new ArrayList<>(items.size());
            for (int i = 0; i < items.size(); i++) {
                JSONObject item = items.getJSONObject(i);
                if (item != null) {
                    list.add(item);
                }
            }
            return list;
        } catch (Exception e) {
            logger.warn("[AGGREGATE] 上游 {} 列表拉取失败: {}", upstream.getName(), e.getMessage());
            return java.util.Collections.emptyList();
        } finally {
            McpIdentityContext.clear();
        }
    }

    private McpResponse aggregateToolsCall(McpRequest request, String gatewaySessionId,
                                           List<UpstreamDef> upstreams,
                                           Function<UpstreamDef, String> auth) {
        String fullName = request.getParams() != null ? request.getParams().getName() : null;
        if (!StringUtils.hasText(fullName) || !fullName.contains("__")) {
            return errorResponse(request, "聚合模式下工具名需带命名空间前缀：{upstreamName}__{toolName}");
        }
        int idx = fullName.indexOf("__");
        String upstreamName = fullName.substring(0, idx);
        String toolName = fullName.substring(idx + 2);

        UpstreamDef target = null;
        for (UpstreamDef u : upstreams) {
            if (upstreamName.equals(u.getName())) {
                target = u;
                break;
            }
        }
        if (target == null) {
            return errorResponse(request, "未找到上游：" + upstreamName);
        }

        String token = auth.apply(target);
        String sid = ensureSession(gatewaySessionId, target, token);
        JSONObject req = (JSONObject) JSON.toJSON(request);
        req.getJSONObject("params").put("name", toolName);
        return wrap(request, forwardRaw(target.getUrl(), sid, req, token));
    }

    // ============ 通用辅助 ============

    /**
     * 惰性初始化单个上游并返回其会话 id。
     *
     * <p>并发首包会同时 miss 缓存，原先会各自 initialize 一次；这里按
     * 「网关会话 + 上游名」做单飞，只有一个线程真正 initialize，其余复用结果。
     */
    private String ensureSession(String gatewaySessionId, UpstreamDef upstream, String identityJwt) {
        String key = gatewaySessionId == null ? "anonymous-" + upstream.getName() : gatewaySessionId;
        String cached = upstreamSessions.get(key, upstream.getName());
        if (cached != null) {
            return cached;
        }
        String lockKey = key + "|" + upstream.getName();
        Object lock = sessionInitLocks.computeIfAbsent(lockKey, k -> new Object());
        try {
            synchronized (lock) {
                cached = upstreamSessions.get(key, upstream.getName());
                if (cached != null) {
                    return cached;
                }
                return initializeSession(key, upstream, identityJwt);
            }
        } finally {
            sessionInitLocks.remove(lockKey, lock);
        }
    }

    /** 真正执行 initialize 并写入会话映射；调用方需持有单飞锁 */
    private String initializeSession(String key, UpstreamDef upstream, String identityJwt) {
        JSONObject init = new JSONObject();
        init.put("jsonrpc", "2.0");
        init.put("id", 1);
        init.put("method", "initialize");
        JSONObject params = new JSONObject();
        params.put("protocolVersion", "2024-11-05");
        params.put("capabilities", new JSONObject());
        JSONObject clientInfo = new JSONObject();
        clientInfo.put("name", "mcp-gateway");
        clientInfo.put("version", "1.0.0");
        params.put("clientInfo", clientInfo);
        init.put("params", params);

        try {
            WebClient.RequestBodySpec spec = webClient.post()
                    .uri(upstream.getUrl())
                    .contentType(MediaType.APPLICATION_JSON);
            applyUpstreamAuth(spec, identityJwt);
            ResponseEntity<String> resp = spec
                    .bodyValue(init.toJSONString())
                    .retrieve()
                    .toEntity(String.class)
                    .block(UPSTREAM_TIMEOUT);
            String newSid = resp != null ? resp.getHeaders().getFirst(MCP_SESSION_ID_HEADER) : null;
            if (StringUtils.hasText(newSid)) {
                upstreamSessions.put(key, upstream.getName(), newSid, SESSION_TTL_MS);
            }
            return newSid;
        } catch (Exception e) {
            logger.warn("上游 {} 初始化失败: {}", upstream.getName(), e.getMessage());
            return null;
        }
    }

    private JSONObject forwardRaw(String url, String sessionId, JSONObject request, String identityJwt) {
        WebClient.RequestBodySpec spec = webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Accept", MediaType.APPLICATION_JSON_VALUE + ", " + MediaType.TEXT_EVENT_STREAM_VALUE);
        if (StringUtils.hasText(sessionId)) {
            spec.header(MCP_SESSION_ID_HEADER, sessionId);
        }
        applyUpstreamAuth(spec, identityJwt);
        ResponseEntity<String> resp = spec.bodyValue(request.toJSONString())
                .retrieve()
                .toEntity(String.class)
                .block(UPSTREAM_TIMEOUT);
        String body = resp != null ? resp.getBody() : null;
        if (body == null || body.trim().isEmpty()) {
            return new JSONObject();
        }
        return JSON.parseObject(extractJsonBody(body));
    }

    /**
     * 兼容两种上游响应：普通 JSON 与 Streamable HTTP 的 SSE（取最后一个 data: JSON）。
     */
    private String extractJsonBody(String body) {
        String trimmed = body.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }
        String last = null;
        for (String line : body.split("\\r?\\n")) {
            String l = line.trim();
            if (l.startsWith("data:")) {
                String data = l.substring(5).trim();
                if ((data.startsWith("{") || data.startsWith("["))) {
                    last = data;
                }
            }
        }
        return last != null ? last : trimmed;
    }

    private McpResponse wrap(McpRequest request, JSONObject body) {
        McpResponse resp = new McpResponse();
        resp.setId(request.getId());
        if (body == null || body.isEmpty()) {
            return resp;
        }
        if (body.get("result") != null) {
            resp.setResult(body.get("result"));
        }
        if (body.get("error") != null) {
            resp.setError(body.get("error"));
        }
        return resp;
    }

    private McpResponse ok(McpRequest request, Object result) {
        McpResponse resp = new McpResponse();
        resp.setId(request.getId());
        resp.setResult(result);
        return resp;
    }

    private McpResponse parseResponse(String body, McpRequest request) {
        McpResponse resp = new McpResponse();
        resp.setId(request.getId());
        if (body == null || body.trim().isEmpty()) {
            return resp;
        }
        try {
            McpResponse parsed = JSON.parseObject(extractJsonBody(body), McpResponse.class);
            if (parsed == null) {
                return resp;
            }
            if (parsed.getId() == null) {
                parsed.setId(request.getId());
            }
            return parsed;
        } catch (Exception e) {
            logger.warn("解析上游 MCP 响应失败, 响应长度={}", body.length());
            return errorResponse(request, "上游响应解析失败");
        }
    }

    private McpResponse errorResponse(McpRequest request, String message) {
        return errorResponse(request, -32603, message);
    }

    private McpResponse errorResponse(McpRequest request, int code, String message) {
        McpResponse resp = new McpResponse();
        resp.setId(request.getId());
        JSONObject error = new JSONObject();
        error.put("code", code);
        error.put("message", message);
        resp.setError(error);
        return resp;
    }

    /** 网关来源凭证 + 可选原始用户 token 透传 */
    private void applyUpstreamAuth(WebClient.RequestBodySpec spec, String identityJwt) {
        if (StringUtils.hasText(identityJwt)) {
            spec.header("Authorization", "Bearer " + identityJwt);
        }
        if (identityProperties != null && identityProperties.isForwardUserToken()) {
            String userToken = McpIdentityContext.getUserToken();
            if (StringUtils.hasText(userToken)) {
                spec.header(identityProperties.getUserTokenHeader(), userToken);
            }
        }
    }

    // ============ 会话映射 ============

    private String getSingleSession(String gatewaySessionId) {
        if (gatewaySessionId == null) {
            return null;
        }
        return upstreamSessions.get(gatewaySessionId, SINGLE_UPSTREAM_KEY);
    }

    private void putSingleSession(String gatewaySessionId, String upstreamSessionId) {
        upstreamSessions.put(gatewaySessionId, SINGLE_UPSTREAM_KEY, upstreamSessionId, SESSION_TTL_MS);
    }

    /** 网关会话终止时清理上游会话映射 */
    public void closeSession(String gatewaySessionId) {
        if (gatewaySessionId != null) {
            upstreamSessions.remove(gatewaySessionId);
        }
    }

    @PreDestroy
    public void shutdown() {
        aggregateExecutor.shutdownNow();
    }
}
