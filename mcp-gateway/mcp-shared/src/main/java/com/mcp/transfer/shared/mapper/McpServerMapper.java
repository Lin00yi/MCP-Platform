package com.mcp.transfer.shared.mapper;

import com.mcp.transfer.shared.entity.McpServer;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface McpServerMapper {

    /**
     * 轻量版本戳：行数 + 最大更新时间。
     * 用于数据面兜底轮询（Redis 事件丢失时仍能发现变更），单次查询走主键/索引。
     */
    @Select("SELECT CONCAT(COUNT(*), '|', COALESCE(DATE_FORMAT(MAX(update_time), '%Y%m%d%H%i%s'), '0')) FROM mcp_server")
    String selectRevisionStamp();

    int deleteByPrimaryKey(Integer id);

    int insert(McpServer record);

    int insertSelective(McpServer record);

    McpServer selectByPrimaryKey(Integer id);

    /** 按稳定标识查询（server_key 唯一） */
    McpServer selectByServerKey(@Param("serverKey") String serverKey);

    /** 仅更新稳定标识（创建后回填，避免影响其他字段） */
    int updateServerKey(@Param("id") Integer id, @Param("serverKey") String serverKey);

    int updateByPrimaryKeySelective(McpServer record);

    int updateByPrimaryKey(McpServer record);

    /**
     * 工具暴露配置专用更新：允许把阈值/热工具显式置为 NULL（selective 更新无法清空）。
     */
    int updateToolExposure(@Param("id") Integer id,
                           @Param("toolAccessMode") String toolAccessMode,
                           @Param("toolExposureMode") String toolExposureMode,
                           @Param("promptAccessMode") String promptAccessMode,
                           @Param("resourceAccessMode") String resourceAccessMode,
                           @Param("hotToolCodes") String hotToolCodes,
                           @Param("toolWarnThreshold") Integer toolWarnThreshold,
                           @Param("toolMaxThreshold") Integer toolMaxThreshold,
                           @Param("updateTime") java.util.Date updateTime);

    /** 查询指定状态的全部 server（动态注册用，status=1） */
    List<McpServer> getAllByStatus(@Param("status") Integer status);

    /** 按路径查询启用的 server（MCP 运行时用，status=1） */
    McpServer selectOneByPath(@Param("path") String path);

    /** 查询全部 server（管理后台用） */
    List<McpServer> selectAll();

    /** 查询指定多个业务方下的 server（管理后台数据隔离用） */
    List<McpServer> selectByTenantIds(@Param("tenantIds") List<Integer> tenantIds);

    /** 按业务方查询全部 server */
    List<McpServer> selectByTenantId(@Param("tenantId") Integer tenantId);
}
