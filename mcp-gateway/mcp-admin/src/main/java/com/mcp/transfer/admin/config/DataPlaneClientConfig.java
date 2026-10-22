package com.mcp.transfer.admin.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * 控制面访问数据面内部接口的 HTTP 客户端。
 *
 * <p>读超时 60s：embedding 重建是同步接口，工具多时会超过 10s；连通性测试自身有 5s 上游超时。
 * 变更通知失败不应阻塞管理端写操作（调用方自行吞异常）。
 */
@Configuration
public class DataPlaneClientConfig {

    @Bean
    public RestTemplate dataPlaneRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(60))
                .build();
    }
}
