package com.mcp.transfer.port;

import java.util.Map;

/**
 * Embedding 索引管理端口（控制面 → 数据面）。
 *
 * <p>向量索引属于数据面：它才是检索的使用方，也才应该持有 embedding 凭证、拥有出网权限。
 * 控制面只提供「重建 / 查看状态」的按钮，本身不再调用外部 embedding 服务。
 */
public interface EmbeddingAdminPort {

    /** 重建某个 Server 的工具向量索引（同步，返回 indexed/dim 等统计） */
    Map<String, Object> reindex(Integer serverId);

    /** 索引状态（是否启用、已索引数量、失败数、模型等） */
    Map<String, Object> stats();

    /** 数据面不可用 */
    class UnavailableException extends RuntimeException {
        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
