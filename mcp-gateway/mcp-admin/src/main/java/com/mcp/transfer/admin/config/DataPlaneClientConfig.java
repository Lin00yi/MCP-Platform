package com.mcp.transfer.admin.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * 控制面访问数据面内部接口的 HTTP 客户端。
 *
 * <p>{@link #dataPlaneRestTemplate} 读超时 60s：embedding 重建/健康探测是较慢的同步接口，
 * 工具多时会超过 10s；连通性测试自身有 5s 上游超时。
 *
 * <p>{@link #configChangeRestTemplate} 是「变更通知」专用的短超时客户端（1s/2s）：
 * 通知是 best-effort，失败由数据面 revision 轮询兜底，绝不能因为数据面卡顿拖住管理端写操作。
 */
@Configuration
public class DataPlaneClientConfig {

    @Bean
    @Primary
    public RestTemplate dataPlaneRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(60))
                .build();
    }

    /** 配置变更通知专用：短超时，失败只告警 */
    @Bean("configChangeRestTemplate")
    public RestTemplate configChangeRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(1))
                .setReadTimeout(Duration.ofSeconds(2))
                .build();
    }
}
