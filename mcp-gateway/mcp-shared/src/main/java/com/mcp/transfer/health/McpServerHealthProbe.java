package com.mcp.transfer.health;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mcp.transfer.entity.McpServer;
import com.mcp.transfer.entity.McpTool;
import com.mcp.transfer.mapper.McpToolMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;

/**
 * MCP Server 探活器：按 upstream_type 选择探测方式，均无副作用。
 *
 * <ul>
 *   <li>MCP：POST initialize 到 downstream，校验返回合法 JSON-RPC result</li>
 *   <li>AGGREGATE：逐个上游探测，全通过=UP，部分=DEGRADED，全失败=DOWN</li>
 *   <li>NONE：校验本地工具类/方法是否存在（不执行）；纯 REST 工具不主动打（避免副作用）</li>
 * </ul>
 */
@Component
public class McpServerHealthProbe {

    private static final Logger logger = LoggerFactory.getLogger(McpServerHealthProbe.class);

    private static final String INIT_BODY =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"health-probe\",\"version\":\"1.0.0\"}}}";

    @Value("${mcp.health.timeout-ms:2000}")
    private long timeoutMs;

    private final WebClient webClient;

    @Autowired
    private McpToolMapper mcpToolMapper;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private com.mcp.transfer.config.security.UrlSecurityValidator urlSecurityValidator;

    public McpServerHealthProbe() {
        this.webClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }

    public ProbeResult probe(McpServer server) {
        String type = server.getUpstreamType() == null ? "NONE" : server.getUpstreamType().toUpperCase();
        switch (type) {
            case "MCP":
                return probeSingle(server.getUpstreamUrl());
            case "AGGREGATE":
                return probeAggregate(server.getUpstreamUrl());
            default:
                return probeLocal(server);
        }
    }

    /** 单下游：initialize 探活 */
    private ProbeResult probeSingle(String url) {
        if (!StringUtils.hasText(url)) {
            return ProbeResult.fail(0, "未配置 upstream_url");
        }
        long start = System.currentTimeMillis();
        try {
            // 与数据面一致：探活前同样做 SSRF 校验，避免通过 upstream_url 探测内网
            urlSecurityValidator.validate(url);
            ResponseEntity<String> resp = webClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(INIT_BODY)
                    .retrieve()
                    .toEntity(String.class)
                    .block(Duration.ofMillis(timeoutMs));
            long cost = System.currentTimeMillis() - start;
            String body = resp == null ? null : resp.getBody();
            if (!StringUtils.hasText(body) || !body.contains("\"result\"")) {
                return ProbeResult.fail(cost, "响应非合法 MCP initialize");
            }
            return ProbeResult.ok(cost, "initialize ok");
        } catch (Exception e) {
            return ProbeResult.fail(System.currentTimeMillis() - start, "探测失败: " + e.getMessage());
        }
    }

    /** 聚合：逐个上游探测，任一失败即降级 */
    private ProbeResult probeAggregate(String upstreamUrl) {
        long start = System.currentTimeMillis();
        JSONArray arr;
        try {
            arr = JSON.parseArray(upstreamUrl);
        } catch (Exception e) {
            return ProbeResult.fail(0, "upstream_url 不是合法 JSON 数组");
        }
        if (arr == null || arr.isEmpty()) {
            return ProbeResult.fail(0, "未配置上游");
        }
        int okCount = 0;
        StringBuilder failed = new StringBuilder();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String name = o.getString("name");
            String url = o.getString("url");
            ProbeResult r = probeSingle(url);
            if (r.ok) {
                okCount++;
            } else {
                if (failed.length() > 0) failed.append("; ");
                failed.append(name).append(": ").append(r.message);
            }
        }
        long cost = System.currentTimeMillis() - start;
        if (okCount == arr.size()) {
            return ProbeResult.ok(cost, "全部 " + okCount + " 个上游正常");
        }
        if (okCount == 0) {
            return ProbeResult.fail(cost, "全部上游失败 → " + failed);
        }
        return ProbeResult.fail(cost, "部分上游失败(" + (arr.size() - okCount) + "/" + arr.size() + ") → " + failed);
    }

    /** 本地/REST：只校验本地工具类是否存在，不执行、不打下游 */
    private ProbeResult probeLocal(McpServer server) {
        long start = System.currentTimeMillis();
        List<McpTool> tools = mcpToolMapper.selectAllByServerId(server.getId());
        if (tools == null || tools.isEmpty()) {
            return ProbeResult.ok(0, "无工具（可调用但不可发现）");
        }
        for (McpTool t : tools) {
            if (t.getType() == null || t.getType() != 0) {
                continue; // REST 工具不主动探测
            }
            String serviceUrl = t.getServiceUrl();
            if (!StringUtils.hasText(serviceUrl) || !serviceUrl.contains(".")) {
                return ProbeResult.fail(0, "本地工具 " + t.getCode() + " 的 serviceUrl 格式错误");
            }
            String className = serviceUrl.substring(0, serviceUrl.indexOf('.'));
            if (!localBeanExists(className)) {
                return ProbeResult.fail(0, "本地工具类不存在: " + className);
            }
        }
        return ProbeResult.ok(System.currentTimeMillis() - start, "本地工具校验通过（REST 工具不主动探测）");
    }

    private boolean localBeanExists(String simpleName) {
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            try {
                Class<?> type = applicationContext.getType(beanName);
                if (type != null && simpleName.equals(type.getSimpleName())) {
                    return true;
                }
            } catch (Exception ignore) {
                // 某些 bean 取 type 会抛异常，忽略
            }
        }
        return false;
    }
}
