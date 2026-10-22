package com.mcp.transfer.admin.port;

import com.mcp.transfer.port.ConfigChangePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link ConfigChangePublisher} 的远程实现：通过内部接口通知数据面。
 *
 * <p>失败只告警不抛出 —— 管理端的写操作已经落库，缓存失效由数据面的
 * revision 兜底轮询（P2 引入）；不应因为数据面暂时不可用导致管理端 500。
 */
@Component
public class HttpConfigChangePublisher implements ConfigChangePublisher {

    private static final Logger logger = LoggerFactory.getLogger(HttpConfigChangePublisher.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String internalToken;

    @Autowired
    public HttpConfigChangePublisher(RestTemplate dataPlaneRestTemplate,
                                     @Value("${mcp.admin.dataplane-url:http://127.0.0.1:8081}") String baseUrl,
                                     @Value("${mcp.internal.token:}") String internalToken) {
        this.restTemplate = dataPlaneRestTemplate;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.internalToken = internalToken;
    }

    @Override
    public void toolChanged(Integer serverId) {
        publish("tool", serverId);
    }

    @Override
    public void toolChanged(Integer serverId, Integer toolId, String op) {
        publish("tool", serverId, toolId, op);
    }

    @Override
    public void promptChanged(Integer serverId) {
        publish("prompt", serverId);
    }

    @Override
    public void resourceChanged(Integer serverId) {
        publish("resource", serverId);
    }

    @Override
    public void routesChanged() {
        publish("routes", null);
    }

    private void publish(String changeType, Integer serverId) {
        publish(changeType, serverId, null, null);
    }

    private void publish(String changeType, Integer serverId, Integer toolId, String op) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("changeType", changeType);
        body.put("serverId", serverId);
        body.put("toolId", toolId);
        body.put("op", op);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (internalToken != null && !internalToken.trim().isEmpty()) {
            headers.set("X-Internal-Token", internalToken);
        }
        try {
            restTemplate.postForEntity(baseUrl + "/internal/config-changed",
                    new HttpEntity<>(body, headers), Map.class);
        } catch (Exception e) {
            logger.warn("[CHANGE] 通知数据面失败 changeType={}, serverId={}: {}（管理端写操作已生效）",
                    changeType, serverId, e.getMessage());
        }
    }

    private static String trimTrailingSlash(String url) {
        String value = url == null ? "" : url.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
