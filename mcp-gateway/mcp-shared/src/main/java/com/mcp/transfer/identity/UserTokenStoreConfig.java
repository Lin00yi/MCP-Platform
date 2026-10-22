package com.mcp.transfer.identity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 用户 token 存储的兜底配置。
 *
 * <p>并入 user-center 后，由 user-center 注册自己的 {@link UserTokenStore}（读取其会话/token 存储），
 * 本 Bean 因 {@link ConditionalOnMissingBean} 自动不生效。
 */
@Configuration
public class UserTokenStoreConfig {

    @Bean
    @ConditionalOnMissingBean(UserTokenStore.class)
    public UserTokenStore unavailableUserTokenStore() {
        return new UnavailableUserTokenStore();
    }
}
