package com.mcp.transfer.mapper;

import com.mcp.transfer.entity.McpTool;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface McpToolMapper {

    /** 轻量版本戳：行数 + 最大更新时间（数据面兜底轮询用） */
    @Select("SELECT CONCAT(COUNT(*), '|', COALESCE(DATE_FORMAT(MAX(update_time), '%Y%m%d%H%i%s'), '0')) FROM mcp_tool")
    String selectRevisionStamp();

    int deleteByPrimaryKey(Integer id);

    int insert(McpTool record);

    int insertSelective(McpTool record);

    McpTool selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(McpTool record);

    int updateByPrimaryKey(McpTool record);

    /** 根据唯一标识查询 */
    McpTool selectByCode(@Param("code") String code);

    /** 查询指定 server 下所有启用的工具（MCP tools/list 用，status=1） */
    List<McpTool> selectAllByServerId(@Param("serverId") Integer serverId);

    /** 查询指定 server 下指定 code 的启用工具（MCP tools/call 用，status=1） */
    McpTool selectOneByServerIdAndCode(@Param("serverId") Integer serverId, @Param("code") String code);

    /** 查询全部工具（管理后台用） */
    List<McpTool> selectAll();

    /** 查询指定多个 server 下的工具（管理后台数据隔离用） */
    List<McpTool> selectByServerIds(@Param("serverIds") List<Integer> serverIds);

    /** 删除指定 server 下全部工具（级联删除） */
    int deleteByServerId(@Param("serverId") Integer serverId);
}
