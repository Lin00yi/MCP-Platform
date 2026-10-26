package com.mcp.transfer.identity;

/**
 * REQUIRED 模式下无法签发内部身份断言时抛出。
 *
 * <p>数据面捕获后返回 JSON-RPC {@code -32003}，不把请求转发给下游。
 */
public class IdentityRequiredException extends RuntimeException {

    public IdentityRequiredException(String message) {
        super(message);
    }
}
