package com.mcp.transfer.identity;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关解析完成后的运行时身份：app + tenant（app 归属）+ user + 当前客编。
 */
@Data
@AllArgsConstructor
public class ResolvedIdentity {
    private String appId;
    private Integer tenantId;
    private String userId;
    private String userName;
    private String customerId;
    /** 用户 token 的 SHA-256，用于会话绑定；不保存原始 token */
    private String userTokenHash;
    private List<String> scopes;
    private String traceId;

    public ResolvedIdentity() {
        this.scopes = new ArrayList<>();
    }

    /** 会话比对：app/user/customer 任一变化都视为不同身份 */
    public boolean sameAs(ResolvedIdentity other) {
        if (other == null) {
            return false;
        }
        return eq(appId, other.appId)
                && eq(userId, other.userId)
                && eq(customerId, other.customerId)
                && eq(userTokenHash, other.userTokenHash);
    }

    private boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
