package com.mcp.transfer.identity;

import org.springframework.stereotype.Component;

/**
 * 网关侧当前请求的身份上下文（ThreadLocal）。
 * 由 McpAuthInterceptor 写入，McpUpstreamClient / McpServiceImpl 读取，请求结束清理。
 */
@Component
public class McpIdentityContext {

    private static final ThreadLocal<ResolvedIdentity> HOLDER = new ThreadLocal<>();

    /** 原始用户 token，仅用于向可信下游透传；不写日志、不落库 */
    private static final ThreadLocal<String> USER_TOKEN_HOLDER = new ThreadLocal<>();

    public static void set(ResolvedIdentity identity) {
        HOLDER.set(identity);
    }

    public static ResolvedIdentity get() {
        return HOLDER.get();
    }

    public static void setUserToken(String userToken) {
        USER_TOKEN_HOLDER.set(userToken);
    }

    public static String getUserToken() {
        return USER_TOKEN_HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
        USER_TOKEN_HOLDER.remove();
    }
}
