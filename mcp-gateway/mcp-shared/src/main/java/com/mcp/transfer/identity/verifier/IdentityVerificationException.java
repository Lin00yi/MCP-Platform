package com.mcp.transfer.identity.verifier;

/**
 * 下游验签失败异常。
 */
public class IdentityVerificationException extends RuntimeException {
    public IdentityVerificationException(String message) {
        super(message);
    }

    public IdentityVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
