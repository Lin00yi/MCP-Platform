package com.mcp.transfer.dto.mcp.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 向量库检索结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResult {
    
    /**
     * 向量 ID（对应 MySQL 中的政策记录 ID）
     */
    private Long id;
    
    /**
     * 相似度分数 (0-1)
     */
    private Double score;
    
    /**
     * 载荷数据（政策相关信息）
     */
    private Map<String, Object> payload;
}
