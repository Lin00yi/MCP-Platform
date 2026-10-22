package com.mcp.transfer.mapper;

import com.mcp.transfer.entity.McpRequestLog;
import org.apache.ibatis.annotations.Param;

/**
 * MCP 请求日志（安全审计留痕，Authorization 已脱敏 + sk_hash）。
 */
public interface McpRequestLogMapper {
    int insert(McpRequestLog record);

    int insertSelective(McpRequestLog record);

    /** 清理历史请求日志（保留期由配置控制） */
    int deleteOlderThan(@Param("before") java.util.Date before);
}
