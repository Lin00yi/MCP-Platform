package com.mcp.transfer.mapper;

import com.mcp.transfer.entity.OperationLog;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

public interface OperationLogMapper {
    int insert(OperationLog record);

    int insertSelective(OperationLog record);

    /** 分页查询操作日志（可选按 action / username / tenantIds 过滤） */
    List<OperationLog> selectWithPage(@Param("start") int start, @Param("limit") int limit,
                                      @Param("action") String action, @Param("username") String username,
                                      @Param("tenantIds") List<Integer> tenantIds);

    long count(@Param("action") String action, @Param("username") String username,
               @Param("tenantIds") List<Integer> tenantIds);

    /** 清理历史操作日志 */
    int deleteOlderThan(@Param("before") Date before);
}
