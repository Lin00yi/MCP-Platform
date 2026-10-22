package com.mcp.transfer.admin.port;

import com.mcp.transfer.port.EmbeddingAdminPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.Map;

/**
 * {@link EmbeddingAdminPort} 的远程实现。
 *
 * <p>embedding 的 host/key 只配置在数据面，控制面不再持有该凭证；
 * 工具增删改时的索引维护也由数据面在 TOOL 事件里完成。
 */
@Component
public class HttpEmbeddingAdminPort implements EmbeddingAdminPort {

    private static final Logger logger = LoggerFactory.getLogger(HttpEmbeddingAdminPort.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String internalToken;

    @Autowired
    public HttpEmbeddingAdminPort(RestTemplate dataPlaneRestTemplate,
                                  @Value("${mcp.admin.dataplane-url:http://127.0.0.1:8081}") String baseUrl,
                                  @Value("${mcp.internal.token:}") String internalToken) {
        this.restTemplate = dataPlaneRestTemplate;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.internalToken = internalToken;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> reindex(Integer serverId) {
        try {
            Map<String, Object> body = restTemplate.exchange(
                    baseUrl + "/internal/embeddings/reindex/" + serverId,
                    HttpMethod.POST, entity(), Map.class).getBody();
            return body == null ? Collections.emptyMap() : body;
        } catch (HttpClientErrorException.NotFound notFound) {
            throw new UnavailableException("数据面没有该内部接口（版本不匹配）", notFound);
        } catch (Exception e) {
            throw unavailable("重建索引失败", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> stats() {
        try {
            Map<String, Object> body = restTemplate.exchange(
                    baseUrl + "/internal/embeddings/stats",
                    HttpMethod.GET, entity(), Map.class).getBody();
            return body == null ? Collections.singletonMap("enabled", false) : body;
        } catch (Exception e) {
            throw unavailable("读取索引状态失败", e);
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
        logger.warn("[EMBEDDING] {}（数据面不可达）: {}", action, cause.getMessage());
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
