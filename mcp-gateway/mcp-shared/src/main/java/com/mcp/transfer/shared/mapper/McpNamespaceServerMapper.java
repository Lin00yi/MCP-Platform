package com.mcp.transfer.shared.mapper;

import com.mcp.transfer.shared.entity.McpNamespaceServer;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface McpNamespaceServerMapper {

    List<McpNamespaceServer> selectByNamespaceId(@Param("namespaceId") Integer namespaceId);

    List<McpNamespaceServer> selectByServerId(@Param("serverId") Integer serverId);

    int insert(McpNamespaceServer record);

    int deleteByNamespaceId(@Param("namespaceId") Integer namespaceId);
}
