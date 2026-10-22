package com.mcp.transfer.exposure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Prompt / Resource / Tool 结果内容大小限制。 */
@Data
@Component
@ConfigurationProperties(prefix = "mcp.assets")
public class AssetProperties {
    /** prompts/get 单个 text 字段上限 */
    private int promptMaxBytes = 262144;
    /** resources/read 单个 text/blob 字段上限 */
    private int resourceMaxBytes = 1048576;
    /** tools/call 返回文本上限 */
    private int toolResultMaxBytes = 1048576;
}
