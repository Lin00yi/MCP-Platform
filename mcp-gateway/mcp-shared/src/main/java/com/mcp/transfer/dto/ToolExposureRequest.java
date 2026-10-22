package com.mcp.transfer.dto;

import lombok.Data;

import java.util.List;

/**
 * 管理台设置 server 工具暴露策略的请求体。
 *
 * <p>字段为 null 表示保持原值；hotToolCodes 传空数组表示清空热工具配置。
 */
@Data
public class ToolExposureRequest {

    /** WHITELIST / ALL */
    private String toolAccessMode;

    /** DIRECT / META */
    private String toolExposureMode;

    /** Prompt 访问模式：WHITELIST / ALL */
    private String promptAccessMode;

    /** Resource 访问模式：WHITELIST / ALL */
    private String resourceAccessMode;

    /** META 模式常驻 tools/list 的工具 code 列表 */
    private List<String> hotToolCodes;

    /** 工具数告警阈值；<=0 表示恢复全局默认 */
    private Integer toolWarnThreshold;

    /** 工具数硬阈值；<=0 表示恢复全局默认 */
    private Integer toolMaxThreshold;

    /**
     * 切换为 WHITELIST 时，是否为已有 Server 授权的应用补齐当前全部工具授权。
     * null/false = 保持空白名单（应用将看不到工具）。
     */
    private Boolean backfillExistingApps;
}
