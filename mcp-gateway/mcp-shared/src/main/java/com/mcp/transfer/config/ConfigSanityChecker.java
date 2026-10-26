package com.mcp.transfer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 启动期配置自检：把“配置错了但服务仍能启动”的情况提前暴露。
 *
 * <p>只做低成本、确定性的检查，不访问数据库/Redis：
 * <ul>
 *   <li>生产禁用静态 token；</li>
 *   <li>生产 REQUIRED 身份下传必须配置签名私钥；</li>
 *   <li>Redis / 分片 Redis 开启时必须有地址；</li>
 *   <li>生产透传用户 token、放开内网 SSRF 时给出安全告警；</li>
 *   <li>工具档位只允许 default/compact/large。</li>
 * </ul>
 */
@Component
public class ConfigSanityChecker implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(ConfigSanityChecker.class);

    private final Environment environment;

    public ConfigSanityChecker(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
        List<String> fatal = new ArrayList<>();

        if (prod && StringUtils.hasText(environment.getProperty("mcp.auth.user-token.static-tokens"))) {
            fatal.add("生产环境禁止配置 MCP_AUTH_STATIC_TOKENS；请由 user-center 提供 UserTokenStore");
        }

        boolean identityEnabled = Boolean.parseBoolean(environment.getProperty("mcp.identity.enabled", "false"));
        String identityMode = environment.getProperty("mcp.identity.mode", "OPTIONAL");
        String signingKey = environment.getProperty("mcp.identity.signing-private-key", "");
        if (prod && identityEnabled && "REQUIRED".equalsIgnoreCase(identityMode)
                && !StringUtils.hasText(signingKey)) {
            fatal.add("MCP_IDENTITY_MODE=REQUIRED 时必须配置 MCP_IDENTITY_SIGNING_KEY");
        }
        if (!isKnownIdentityMode(identityMode)) {
            fatal.add("MCP_IDENTITY_MODE 仅支持 OFF / OPTIONAL / REQUIRED，当前：" + identityMode);
        }

        boolean redisEnabled = Boolean.parseBoolean(environment.getProperty("mcp.redis.enabled", "false"));
        String redisUri = environment.getProperty("mcp.redis.uri", "");
        if (redisEnabled && !StringUtils.hasText(redisUri)) {
            fatal.add("MCP_REDIS_ENABLED=true 时必须配置 MCP_REDIS_URL");
        }

        String toolProfile = environment.getProperty("MCP_TOOLS_PROFILE", "default");
        if (!isKnownToolProfile(toolProfile)) {
            fatal.add("MCP_TOOLS_PROFILE 仅支持 default / compact / large，当前：" + toolProfile);
        }

        if (prod && Boolean.parseBoolean(environment.getProperty("mcp.identity.forward-user-token", "false"))) {
            logger.warn("[CONFIG] 生产环境已开启 MCP_IDENTITY_FORWARD_USER_TOKEN，原始用户 token 会透传给下游；请确认下游可信");
        }
        if (prod && Boolean.parseBoolean(environment.getProperty("mcp.security.allow-private-network", "false"))) {
            logger.warn("[CONFIG] 生产环境已开启 MCP_SECURITY_ALLOW_PRIVATE_NETWORK，SSRF 防护被放开；请确认网络边界");
        }
        if (prod && environment.getProperty("mcp.public-base-url", "").contains("127.0.0.1")) {
            logger.warn("[CONFIG] 生产环境 MCP_PUBLIC_BASE_URL 仍指向 127.0.0.1，discovery 返回的地址客户端可能不可达");
        }

        if (!fatal.isEmpty()) {
            throw new IllegalStateException("配置自检失败：\n- " + String.join("\n- ", fatal));
        }
        logger.info("[CONFIG] 配置自检通过（profile={}, identity={}/{}, redis={}）",
                prod ? "prod" : "non-prod", identityEnabled, identityMode.toUpperCase(Locale.ROOT),
                redisEnabled);
    }

    private boolean isKnownIdentityMode(String mode) {
        String value = mode == null ? "" : mode.trim().toUpperCase(Locale.ROOT);
        return "OFF".equals(value) || "OPTIONAL".equals(value) || "REQUIRED".equals(value);
    }

    private boolean isKnownToolProfile(String profile) {
        String value = profile == null ? "default" : profile.trim().toLowerCase(Locale.ROOT);
        return "default".equals(value) || "compact".equals(value) || "large".equals(value);
    }
}
