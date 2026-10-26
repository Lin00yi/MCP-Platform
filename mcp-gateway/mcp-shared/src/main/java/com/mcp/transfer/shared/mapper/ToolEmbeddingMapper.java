package com.mcp.transfer.shared.mapper;

import com.mcp.transfer.shared.entity.ToolEmbedding;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ToolEmbeddingMapper {

    ToolEmbedding selectByToolId(@Param("toolId") Integer toolId);

    List<ToolEmbedding> selectByServerId(@Param("serverId") Integer serverId);

    int upsert(ToolEmbedding embedding);

    int deleteByToolId(@Param("toolId") Integer toolId);

    int deleteByServerId(@Param("serverId") Integer serverId);
}
