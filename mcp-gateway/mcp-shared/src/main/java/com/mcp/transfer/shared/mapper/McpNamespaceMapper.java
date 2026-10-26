package com.mcp.transfer.shared.mapper;

import com.mcp.transfer.shared.entity.McpNamespace;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface McpNamespaceMapper {

    List<McpNamespace> selectAll();

    McpNamespace selectByPrimaryKey(@Param("id") Integer id);

    McpNamespace selectByPrefix(@Param("prefix") String prefix);

    int insertSelective(McpNamespace record);

    int updateByPrimaryKeySelective(McpNamespace record);

    int deleteByPrimaryKey(@Param("id") Integer id);
}
