package com.mcp.transfer.identity;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 用户 token 认证配置（{@code mcp.auth.user-token.*}）。
 *
 * <p>目标态只认 {@code x-dsh-auth-token}；校验由 user-center 提供的 {@link UserTokenStore} 本地完成，
 * 不需要地址或服务凭证配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mcp.auth.user-token")
public class UserTokenProperties {

    /** 认证头；固定为 x-dsh-auth-token */
    private String header = "x-dsh-auth-token";

    /** 身份缓存 TTL（秒）；0=不缓存 */
    private int cacheTtlSeconds = 30;
}
