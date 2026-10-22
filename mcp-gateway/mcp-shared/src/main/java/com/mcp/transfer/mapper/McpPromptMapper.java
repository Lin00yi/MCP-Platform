package com.mcp.transfer.mapper;

import com.mcp.transfer.entity.McpPrompt;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface McpPromptMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(McpPrompt record);

    int insertSelective(McpPrompt record);

    McpPrompt selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(McpPrompt record);

    int updateByPrimaryKey(McpPrompt record);

    /** 查询指定 server 下所有启用的 prompt（status=1） */
    List<McpPrompt> selectAllByServerId(@Param("serverId") Integer serverId);

    /** 查询全部 prompt（管理后台用） */
    List<McpPrompt> selectAll();

    /** 查询指定多个 server 下的 prompt（管理后台数据隔离用） */
    List<McpPrompt> selectByServerIds(@Param("serverIds") List<Integer> serverIds);

    /** 按 server + name 查询启用的 prompt（prompts/get 用） */
    McpPrompt selectOneByServerIdAndName(@Param("serverId") Integer serverId, @Param("name") String name);

    /** 删除指定 server 下全部 prompt（级联删除） */
    int deleteByServerId(@Param("serverId") Integer serverId);
}
