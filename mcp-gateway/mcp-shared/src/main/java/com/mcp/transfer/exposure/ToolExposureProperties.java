package com.mcp.transfer.exposure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 工具规模治理相关配置（前缀 mcp.tools）。
 *
 * <p>设计目标：把“工具太多导致模型上下文爆炸”的控制点放到网关侧，
 * 通过访问模式（谁能看到）、暴露模式（一次看多少）、分页与 Schema 瘦身共同限流。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mcp.tools")
public class ToolExposureProperties {

    /** DIRECT 模式 tools/list 单页大小；超过后返回 nextCursor（MCP 标准分页） */
    private int pageSize = 200;

    /** 工具数低于该值一律整包返回，避免不跟随 nextCursor 的客户端静默丢工具 */
    private int paginationMinTools = 500;

    /** Prompt/Resource 数量低于该值一律整包返回 */
    private int assetPaginationMin = 500;

    /** 允许分页的客户端名称正则（逗号分隔，大小写不敏感）；空=不分页客户端限制 */
    private String paginationClients = "codex,claude,dsh,cursor,workbuddy";

    /** 未识别客户端且工具数超过阈值时的策略：PAGINATE（默认）/ ALL */
    private String paginationUnknownPolicy = "PAGINATE";

    /** 单个应用可见工具数的告警阈值（超过即 WARN 日志 + 管理台标记） */
    private int warnThreshold = 100;

    /** 单个应用可见工具数的硬阈值（超过即 ERROR 日志 + 管理台高亮；不阻断调用） */
    private int maxThreshold = 500;

    /** META 模式 search_tools 默认返回条数 */
    private int searchLimit = 10;

    /** search_tools 允许的最大返回条数 */
    private int maxSearchLimit = 50;

    /** META 模式热工具（常驻 tools/list）最大数量 */
    private int hotToolLimit = 50;

    /** DIRECT 模式下单个工具 description 的最大字符数（0=不截断） */
    private int maxDescriptionLength = 1200;

    /** search_tools 结果里单条 description 的最大字符数 */
    private int searchDescriptionLength = 300;

    /** 是否移除 inputSchema 中的 examples 等非必要字段（E 方案：Schema 瘦身） */
    private boolean trimSchema = true;

    /** tools/list 是否附带 server 元数据（每个工具一份，工具多时建议关闭） */
    private boolean includeServerMeta = false;

    /** 同一 server 同类告警的最小间隔（秒），避免每次 tools/list 刷屏 */
    private int warnThrottleSeconds = 600;

    /** META 模式全局开关；关闭后即使 server 配置 META 也按 DIRECT 处理（应急回退） */
    private boolean metaEnabled = true;

    /**
     * 对外工具名最大长度（含 namespace 前缀）。
     * 超长时截断并追加短哈希，避免主流模型接口 64 字符上限拒收；0=不限制。
     */
    private int maxExposedNameLength = 64;

    /**
     * search_tools 检索策略：
     * AUTO（默认，query 为空走目录、否则 BM25/混合）、CATALOG、KEYWORD、BM25、VECTOR、HYBRID。
     */
    private String searchMode = "AUTO";

    /** 单次工具结果超过该字符数时截断暂存，并提供 read_result_chunk；<=0 关闭 */
    private int resultSpillThreshold = 4000;

    /** 暂存结果的最大字符数（超过后只保留前段，避免内存被打爆） */
    private int resultSpillMaxChars = 4_000_000;

    /** read_result_chunk 默认分片字符数 */
    private int chunkLimit = 3000;

    /** 分片缓存 TTL（秒） */
    private int chunkTtlSeconds = 1800;

    /** 分片缓存最大条数（LRU） */
    private int chunkCacheSize = 200;

    /** 是否启用确定性的离线元数据增强（code/name 拆词 + 中英同义词） */
    private boolean metadataEnrich = true;
}
