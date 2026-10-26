package com.mcp.transfer.shared.entity;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * MCP请求日志记录表
 */
@Data
@Accessors(chain = true)
public class McpRequestLog implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;

    /** MCP协议方法名（如 initialize, tools/list, tools/call） */
    private String mcpMethod;

    /** 实际调用的方法名（仅 tools/call 时有值，如 getPolicyRegion） */
    private String methodName;

    /** Authorization请求头（脱敏后） */
    private String authorization;

    /** Authorization 中 sk 的 SHA-256 哈希（用于联表，避免明文落库） */
    private String skHash;

    /** 调用用户ID（来自身份下传，不读取客户端自报头） */
    private String userId;

    /** 业务方(租户)ID */
    private Integer tenantId;

    /** 请求参数（完整入参JSON） */
    private String requestParams;

    /** 响应结果（完整出参JSON） */
    private String responseResult;

    /** 创建时间 */
    private Date createTime;
}
