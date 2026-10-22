package com.mcp.transfer.admin.service.openapi;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAPI → Tool 的导入计划（preview 返回；import 复用同一套解析结果）。
 */
@Data
public class OpenApiImportPlan {

    private String specTitle;
    private String specVersion;
    private String openapiVersion;
    /** 生效的 baseUrl（已去掉结尾 /） */
    private String baseUrl;

    private int totalOperations;
    private int ready;
    private int conflicts;
    private int errors;
    private int warnings;

    private List<OpenApiToolDraft> tools = new ArrayList<>();
    /** 文档级问题（版本/路径/服务器地址等） */
    private List<OpenApiIssue> issues = new ArrayList<>();

    public void addIssue(OpenApiIssue issue) {
        issues.add(issue);
    }

    public boolean hasBlockingIssue() {
        for (OpenApiIssue i : issues) {
            if (OpenApiIssue.ERROR.equals(i.getLevel())) {
                return true;
            }
        }
        return false;
    }
}
