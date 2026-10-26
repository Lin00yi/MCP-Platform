package com.mcp.transfer.shared.dto;

import lombok.Data;

import java.util.List;

/**
 * 管理台设置 Server 工具暴露策略的请求体。
 *
 * <p>应用授权已移除，不再接受访问模式（WHITELIST/ALL）；
 * 字段为 null 表示保持原值，hotToolCodes 传空数组表示清空热工具配置。
 */
@Data
public class ToolExposureRequest {

    /** DIRECT / META */
    private String toolExposureMode;

    /** META 模式常驻 tools/list 的工具 code 列表 */
    private List<String> hotToolCodes;

    /** 工具数告警阈值；<=0 表示恢复全局默认 */
    private Integer toolWarnThreshold;

    /** 工具数硬阈值；<=0 表示恢复全局默认 */
    private Integer toolMaxThreshold;
}
