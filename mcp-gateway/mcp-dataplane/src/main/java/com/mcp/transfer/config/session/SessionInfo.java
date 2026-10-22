package com.mcp.transfer.config.session;

import com.mcp.transfer.identity.ResolvedIdentity;
import lombok.Data;

/**
 * MCP 会话上下文：过期时间 + 绑定的身份（user/customer/tokenHash）。
 *
 * <p>应用授权与 tenant 断言已移除；会话只绑定用户维度，防止中途换人/换客编。
 */
@Data
public class SessionInfo {
    private long expiresAt;
    private String userId;
    private String customerId;
    /** 用户 token 的 SHA-256，用于防止会话中途换 token */
    private String userTokenHash;
    /** MCP initialize 上报的客户端信息，用于分页能力判断 */
    private String clientName;
    private String clientVersion;

    public SessionInfo() {
    }

    public SessionInfo(long expiresAt) {
        this.expiresAt = expiresAt;
    }

    public void bind(ResolvedIdentity identity) {
        if (identity == null) {
            return;
        }
        this.userId = identity.getUserId();
        this.customerId = identity.getCustomerId();
        this.userTokenHash = identity.getUserTokenHash();
    }

    /** 会话身份比对：user/customer/tokenHash 任一变化都视为不同身份 */
    public boolean matches(ResolvedIdentity identity) {
        if (identity == null) {
            return true;
        }
        return eq(userId, identity.getUserId())
                && eq(customerId, identity.getCustomerId())
                && eq(userTokenHash, identity.getUserTokenHash());
    }

    private boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
