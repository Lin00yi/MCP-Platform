package com.mcp.transfer.exposure;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

/**
 * OpenAI 兼容的 embedding HTTP 客户端。
 *
 * <p>配置示例（application-dev.yml）：
 * <pre>
 * embedding:
 *   host: https://embedding.example.com
 *   path: /v1/embeddings
 *   model: text-embedding-v4-fast
 *   key: ${MCP_EMBEDDING_KEY:}
 * </pre>
 * 未配置 host/key 时 {@link #enabled()} 为 false，检索自动降级。
 */
@Component
public class HttpEmbeddingClient implements EmbeddingClient {

    private static final Logger logger = LoggerFactory.getLogger(HttpEmbeddingClient.class);

    @Autowired
    private EmbeddingProperties properties;

    private volatile RestTemplate restTemplate;

    @Override
    public boolean enabled() {
        return properties.isEnabled()
                && StringUtils.hasText(properties.getHost())
                && StringUtils.hasText(properties.getKey());
    }

    @Override
    public float[] embed(String text) {
        if (!enabled() || !StringUtils.hasText(text)) {
            return null;
        }
        try {
            JSONObject body = new JSONObject();
            body.put("model", properties.getModel());
            body.put("input", text);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(properties.getKey());
            String url = trimRight(properties.getHost()) + properties.getPath();
            String response = restTemplate().postForObject(url, new HttpEntity<>(body.toJSONString(), headers), String.class);
            if (!StringUtils.hasText(response)) {
                return null;
            }
            JSONObject json = JSON.parseObject(response);
            JSONArray data = json.getJSONArray("data");
            if (data == null || data.isEmpty()) {
                return null;
            }
            JSONArray vector = data.getJSONObject(0).getJSONArray("embedding");
            if (vector == null) {
                return null;
            }
            float[] result = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) {
                result[i] = vector.getFloatValue(i);
            }
            return result;
        } catch (Exception e) {
            logger.warn("[EMBEDDING] 向量化失败，降级词法检索: {}", e.getMessage());
            return null;
        }
    }

    private RestTemplate restTemplate() {
        if (restTemplate == null) {
            synchronized (this) {
                if (restTemplate == null) {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout(properties.getTimeoutMs());
                    factory.setReadTimeout(properties.getTimeoutMs());
                    restTemplate = new RestTemplate(factory);
                }
            }
        }
        return restTemplate;
    }

    private String trimRight(String value) {
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
