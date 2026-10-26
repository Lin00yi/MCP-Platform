package com.mcp.transfer.identity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 用户 token 存储的兜底配置。
 *
 * <p>优先级：
 * <ol>
 *   <li>user-center 注册自己的 {@link UserTokenStore}（读取其会话/token 存储）；</li>
 *   <li>显式配置 {@code MCP_AUTH_STATIC_TOKENS} 时，使用本地静态 token（仅限开发/E2E）；</li>
 *   <li>否则使用 {@link UnavailableUserTokenStore}，受保护请求返回 503。</li>
 * </ol>
 */
@Configuration
public class UserTokenStoreConfig {

    @Bean
    @ConditionalOnMissingBean(UserTokenStore.class)
    @ConditionalOnProperty(name = "mcp.auth.user-token.static-tokens", matchIfMissing = false)
    public UserTokenStore staticUserTokenStore(UserTokenProperties properties) {
        return new StaticUserTokenStore(properties.getStaticTokens());
    }

    @Bean
    @ConditionalOnMissingBean(UserTokenStore.class)
    public UserTokenStore unavailableUserTokenStore() {
        return new UnavailableUserTokenStore();
    }
}
