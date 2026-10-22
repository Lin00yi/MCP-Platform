package com.mcp.transfer.exposure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Embedding 服务配置（复用 application-dev.yml 的 embedding 段）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "embedding")
public class EmbeddingProperties {

    /** 服务根地址，例如 https://embedding.example.com */
    private String host;

    /** OpenAI 兼容路径 */
    private String path = "/v1/embeddings";

    private String model = "text-embedding-v4-fast";

    private int size = 1024;

    private String key;

    private int timeoutMs = 3000;

    /** 总开关；未配置 key 时也会自动降级为关闭 */
    private boolean enabled = true;
}
