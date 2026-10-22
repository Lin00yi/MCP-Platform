package com.mcp.transfer.mapper;

import com.mcp.transfer.entity.McpEndpointNamespace;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface McpEndpointNamespaceMapper {

    List<McpEndpointNamespace> selectByEndpointId(@Param("endpointId") Integer endpointId);

    List<McpEndpointNamespace> selectByNamespaceId(@Param("namespaceId") Integer namespaceId);

    int insert(McpEndpointNamespace record);

    int deleteByEndpointId(@Param("endpointId") Integer endpointId);

    int deleteByNamespaceId(@Param("namespaceId") Integer namespaceId);
}
