package com.mcp.transfer.identity;

/**
 * 用户 token 存储抽象：由 user-center 提供本地实现（同进程直接查会话/token 存储）。
 *
 * <p>网关不再通过 HTTP introspection 或共享 Redis 读取 user-center；并入 user-center 后，
 * 只需提供一个 {@code UserTokenStore} Bean，网关侧无需任何地址/服务凭证配置。
 */
public interface UserTokenStore {

    /**
     * @param rawToken {@code x-dsh-auth-token} 的原始值
     * @return 有效身份；token 无效/过期/已吊销返回 null
     * @throws UserTokenUnavailableException 存储不可用（映射为 503）
     */
    UserIdentity find(String rawToken);
}
