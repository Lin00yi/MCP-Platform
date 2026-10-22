package com.mcp.transfer.admin.config;

import com.mcp.transfer.admin.security.AdminJwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;

/**
 * 把 Spring 配置中的 {@code mcp.admin.jwt-secret} 注入静态工具类 {@link AdminJwtUtil}，
 * 保证无论通过环境变量、启动参数还是 yml 配置，签名/验签使用同一份密钥。
 */
@Component
public class AdminJwtConfig {

    @Value("${mcp.admin.jwt-secret:}")
    private String adminJwtSecret;

    @PostConstruct
    public void init() {
        if (StringUtils.hasText(adminJwtSecret)) {
            AdminJwtUtil.setSecret(adminJwtSecret);
        }
    }
}
