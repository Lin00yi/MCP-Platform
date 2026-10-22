package com.mcp.transfer.admin.service.openapi;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OpenAPI 导入过程中的一条问题记录。
 * ERROR = 阻塞该项导入；WARN = 可导入但需人工确认。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OpenApiIssue {

    public static final String ERROR = "ERROR";
    public static final String WARN = "WARN";

    private String level;
    private String scope;
    private String message;

    public static OpenApiIssue error(String scope, String message) {
        return new OpenApiIssue(ERROR, scope, message);
    }

    public static OpenApiIssue warn(String scope, String message) {
        return new OpenApiIssue(WARN, scope, message);
    }
}
