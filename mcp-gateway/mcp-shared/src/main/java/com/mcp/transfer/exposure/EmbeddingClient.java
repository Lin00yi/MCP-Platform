package com.mcp.transfer.exposure;

/**
 * 文本向量化接口；不可用时返回 null，由检索层降级到词法匹配。
 */
public interface EmbeddingClient {

    /** 返回向量；失败/未配置返回 null */
    float[] embed(String text);

    boolean enabled();
}
