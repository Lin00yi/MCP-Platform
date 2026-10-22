package com.mcp.transfer.identity;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 身份下传配置（方案 A：JWE 外层 + JWS 内层）。
 *
 * <p>默认关闭，部署时通过 {@code mcp.identity.enabled=true} 打开；关闭时网关行为与改造前一致。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mcp.identity")
public class IdentityProperties {

    /** 总开关 */
    private boolean enabled = false;

    /** JWE / JWS；客编敏感场景建议 JWE */
    private String tokenFormat = "JWE";

    /** 内部断言 iss */
    private String issuer = "mcp-gateway";

    /** 网关签名私钥（PEM，PKCS#8；也可用 Base64 包裹） */
    private String signingPrivateKey;

    /** 签名 key id，用于下游公钥轮换 */
    private String signingKid = "gw-1";

    /** 断言有效期（秒） */
    private long ttlSeconds = 60;

    /** 用户 token 请求头（与 DSH 客户端一致） */
    private String userTokenHeader = "x-dsh-auth-token";

    /** 是否把原始用户 token 透传给下游 MCP Server（默认 false） */
    private boolean forwardUserToken = false;

    /** REST 工具是否透传客编/用户头（默认 false，避免泄露给公网 API） */
    private boolean propagateToRestTools = false;

    /** tools/call 参数中可能被模型篡改的客编字段名；存在时由网关覆盖 */
    private String customerArgNames = "customerId,customer_id,custId";
}
