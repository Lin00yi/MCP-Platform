package com.mcp.transfer.mapper;

import com.mcp.transfer.entity.McpEndpoint;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface McpEndpointMapper {

    List<McpEndpoint> selectAll();

    McpEndpoint selectByPrimaryKey(@Param("id") Integer id);

    McpEndpoint selectByEndpointKey(@Param("endpointKey") String endpointKey);

    int insertSelective(McpEndpoint record);

    int updateByPrimaryKeySelective(McpEndpoint record);

    int deleteByPrimaryKey(@Param("id") Integer id);
}
