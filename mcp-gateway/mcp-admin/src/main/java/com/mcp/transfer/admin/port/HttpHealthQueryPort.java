package com.mcp.transfer.admin.port;

import com.mcp.transfer.port.HealthQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * {@link HealthQueryPort} 的远程实现：健康数据由数据面探测并提供。
 *
 * <p>这样控制面不需要上游出网权限，也不会在展示健康页时额外打一遍上游。
 */
@Component
public class HttpHealthQueryPort implements HealthQueryPort {

    private static final Logger logger = LoggerFactory.getLogger(HttpHealthQueryPort.class);

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_TYPE =
            new ParameterizedTypeReference<List<Map<String, Object>>>() {
            };

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String internalToken;

    @Autowired
    public HttpHealthQueryPort(RestTemplate dataPlaneRestTemplate,
                               @Value("${mcp.admin.dataplane-url:http://127.0.0.1:8081}") String baseUrl,
                               @Value("${mcp.internal.token:}") String internalToken) {
        this.restTemplate = dataPlaneRestTemplate;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.internalToken = internalToken;
    }

    @Override
    public List<Map<String, Object>> listAll() {
        try {
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    baseUrl + "/internal/health/servers", HttpMethod.GET, entity(), LIST_TYPE);
            List<Map<String, Object>> body = response.getBody();
            return body == null ? Collections.emptyList() : body;
        } catch (Exception e) {
            throw unavailable("读取健康状态失败", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> one(Integer serverId) {
        try {
            Map<String, Object> body = restTemplate.exchange(
                    baseUrl + "/internal/health/servers/" + serverId, HttpMethod.GET, entity(), Map.class).getBody();
            return body;
        } catch (HttpClientErrorException.NotFound notFound) {
            return null;
        } catch (Exception e) {
            throw unavailable("读取健康状态失败", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> probe(Integer serverId) {
        try {
            return restTemplate.exchange(
                    baseUrl + "/internal/health/servers/" + serverId + "/probe",
                    HttpMethod.POST, entity(), Map.class).getBody();
        } catch (HttpClientErrorException.NotFound notFound) {
            return null;
        } catch (Exception e) {
            throw unavailable("探测失败", e);
        }
    }

    private HttpEntity<Void> entity() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (internalToken != null && !internalToken.trim().isEmpty()) {
            headers.set("X-Internal-Token", internalToken);
        }
        return new HttpEntity<>(headers);
    }

    private UnavailableException unavailable(String action, Exception cause) {
        logger.warn("[HEALTH] {}（数据面不可达）: {}", action, cause.getMessage());
        return new UnavailableException(action + "：数据面不可达（" + cause.getMessage() + "）", cause);
    }

    private static String trimTrailingSlash(String url) {
        String value = url == null ? "" : url.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
