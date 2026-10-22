package com.mcp.transfer.identity;

/**
 * 尚未接入 user-center 时的兜底实现：明确返回 503，而不是把请求当成匿名或 token 无效。
 *
 * <p>并入 user-center 后，由 user-center 提供 {@link UserTokenStore} Bean，本实现自动退出。
 */
public class UnavailableUserTokenStore implements UserTokenStore {

    @Override
    public UserIdentity find(String rawToken) {
        throw new UserTokenUnavailableException("尚未接入 user-center 用户 token 存储");
    }
}
