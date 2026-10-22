package com.mcp.transfer.identity;

/**
 * 用户 token 校验器：把 {@code x-dsh-auth-token} 换成可信用户身份。
 *
 * <p>约定：
 * <ul>
 *   <li>token 无效 / 过期 / 已吊销 → 返回 {@code null}；</li>
 *   <li>校验存储（user-center 本地 token store）不可用 → 抛 {@link UserTokenUnavailableException}，
 *       由入口映射为 503，避免客户端误删 token。</li>
 * </ul>
 */
public interface UserTokenVerifier {

    /**
     * @param rawToken {@code x-dsh-auth-token} 的原始值
     * @return 有效身份；无效返回 null
     * @throws UserTokenUnavailableException 校验依赖不可用
     */
    UserIdentity verify(String rawToken);
}
