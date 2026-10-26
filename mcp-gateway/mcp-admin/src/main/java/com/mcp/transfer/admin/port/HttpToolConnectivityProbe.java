package com.mcp.transfer.admin.port;

import com.mcp.transfer.shared.entity.McpTool;
import com.mcp.transfer.port.ToolConnectivityProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link ToolConnectivityProbe} 的远程实现：由数据面发起真实试调。
 *
 * <p>上游出网、SSRF 校验、sk 换 token 都留在数据面，控制面不持有这些能力，
 * 也不需要内网出网权限。
 */
@Component
public class HttpToolConnectivityProbe implements ToolConnectivityProbe {

    private static final Logger logger = LoggerFactory.getLogger(HttpToolConnectivityProbe.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String internalToken;

    @Autowired
    public HttpToolConnectivityProbe(RestTemplate dataPlaneRestTemplate,
                                     @Value("${mcp.admin.dataplane-url:http://127.0.0.1:8081}") String baseUrl,
                                     @Value("${mcp.internal.token:}") String internalToken) {
        this.restTemplate = dataPlaneRestTemplate;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.internalToken = internalToken;
    }

    @Override
    public Map<String, Object> test(McpTool tool, String authorization) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tool", tool);
        body.put("authorization", authorization);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (internalToken != null && !internalToken.trim().isEmpty()) {
            headers.set("X-Internal-Token", internalToken);
        }
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    baseUrl + "/internal/tools/test-connect", new HttpEntity<>(body, headers), Map.class);
            if (response.getBody() != null) {
                return response.getBody();
            }
            return failure("数据面返回空响应");
        } catch (Exception e) {
            logger.warn("[PROBE] 调用数据面连通性测试失败: {}", e.getMessage());
            return failure("调用数据面失败：" + e.getMessage());
        }
    }

    private Map<String, Object> failure(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("error", message);
        return result;
    }

    private static String trimTrailingSlash(String url) {
        String value = url == null ? "" : url.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
