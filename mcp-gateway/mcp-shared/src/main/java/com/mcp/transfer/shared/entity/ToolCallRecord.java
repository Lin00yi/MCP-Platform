package com.mcp.transfer.shared.entity;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 工具调用记录表
 */
@Data
@Accessors(chain = true)
public class ToolCallRecord implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;

    /** 租户id */
    private Integer tenantId;

    /** 租户名称 */
    private String tenantName;

    /** Server id */
    private Integer serverId;

    /** Server 名称 */
    private String serverName;

    /** 调用方应用ID（token 的 iss / 请求属性 mcpAppId） */
    private String appId;

    /** 调用用户ID（仅来自身份下传） */
    private String userId;

    /** 调用用户名称 */
    private String userName;

    /** 方法id */
    private Integer methodDefId;

    /** 方法code */
    private String methodCode;

    /** 方法名称 */
    private String methodName;

    /** 请求参数（arguments） */
    private String requestArgs;

    /** 响应结果 */
    private String responseResult;

    /** 调用类型 LOCAL/EXTERNAL */
    private String callType;

    /** HTTP方法 GET/POST */
    private String httpMethod;

    /** 目标URL（外部调用时） */
    private String targetUrl;

    /** 是否成功 1:成功 0:失败 */
    private Integer success;

    /** 错误信息 */
    private String errorMsg;

    /** 耗时(毫秒) */
    private Long duration;

    /** 创建时间 */
    private Date createTime;
}
