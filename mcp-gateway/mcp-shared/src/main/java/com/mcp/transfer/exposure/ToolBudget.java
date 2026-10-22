package com.mcp.transfer.exposure;

import lombok.Data;

/**
 * 单个 server 在“某个应用视角”下的工具预算快照，供告警、管理台与发现接口使用。
 */
@Data
public class ToolBudget {

    private Integer serverId;

    private String serverName;

    /** WHITELIST / ALL */
    private String accessMode;

    /** DIRECT / META */
    private String exposureMode;

    /** server 下启用工具总数 */
    private int totalTools;

    /** 当前应用可见工具数（管理台无 appId 时按最坏情况=全量） */
    private int visibleTools;

    /** META 模式常驻热工具数 */
    private int hotTools;

    private int warnThreshold;

    private int maxThreshold;

    private boolean overWarn;

    private boolean overMax;

    /** 预估 tools/list 定义 token 数（按 300 tokens/工具粗估，仅用于管理台提示） */
    private long estimatedTokens;

    /** Prompt 访问模式：WHITELIST / ALL */
    private String promptAccessMode;

    /** Resource 访问模式：WHITELIST / ALL */
    private String resourceAccessMode;

    private int totalPrompts;

    private int visiblePrompts;

    private int totalResources;

    private int visibleResources;

    private int promptWarnThreshold;

    private int resourceWarnThreshold;

    private boolean promptOverWarn;

    private boolean resourceOverWarn;

    private long promptEstimatedTokens;

    private long resourceEstimatedTokens;
}
