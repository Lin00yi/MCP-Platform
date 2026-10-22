package com.mcp.transfer.identity;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户信息服务返回的单个用户身份（单当前客编）。
 */
@Data
public class UserIdentity {
    private String userId;
    private String userName;
    /** 当前客编（单值，必须返回） */
    private String customerId;
    private List<String> scopes = new ArrayList<>();
    /** 过期时间（epoch 秒，由 user-center 返回） */
    private Long expiresAt;
    /** token 类型：dsh / pat */
    private String tokenType;
    /** token 记录 ID，仅用于审计，不对外暴露 */
    private String tokenId;
    /** 客户端标签（如 codex-mac），仅用于审计 */
    private String clientLabel;
}
