package com.mcp.transfer.identity.verifier;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 下游 MCP Server 验签后得到的身份上下文。
 */
@Data
public class VerifiedIdentity {
    private String appId;
    private String userId;
    private Integer tenantId;
    private String customerId;
    private List<String> scopes = new ArrayList<>();
    private String traceId;
    private String jwtId;
    private Long expiresAt;
}
