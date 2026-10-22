package com.mcp.transfer.admin.service.openapi;

import com.alibaba.fastjson.JSONObject;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAPI 单个 operation 转换出的 Tool 草稿（尚未落库）。
 */
@Data
public class OpenApiToolDraft {

    /** READY 可导入 / CONFLICT Server 下已存在同 code / ERROR 该校验不通过 */
    public static final String READY = "READY";
    public static final String CONFLICT = "CONFLICT";
    public static final String ERROR = "ERROR";

    /** MCP 工具名（mcp_tool.code） */
    private String code;
    /** 展示名（mcp_tool.name） */
    private String name;
    private String description;
    /** GET / POST */
    private String method;
    /** OpenAPI 原始路径，如 /users/{id} */
    private String path;
    private String serviceUrl;
    private String tags;
    private String version;

    private String authType = "NONE";
    private JSONObject authConfig = new JSONObject();

    private JSONObject inputSchema;
    private JSONObject paramMapping;

    private String status = READY;
    private boolean importable = true;
    private List<OpenApiIssue> issues = new ArrayList<>();
}
