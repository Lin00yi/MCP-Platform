package com.mcp.transfer.identity;

/**
 * 用户 token 校验依赖不可用（超时、5xx、服务认证失败等）。
 *
 * <p>与"token 无效"区分：入口应映射为 503，而不是 401；否则客户端会删掉本来有效的 token。
 */
public class UserTokenUnavailableException extends RuntimeException {

    public UserTokenUnavailableException(String message) {
        super(message);
    }

    public UserTokenUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
