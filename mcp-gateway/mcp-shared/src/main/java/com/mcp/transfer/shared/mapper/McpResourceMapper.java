package com.mcp.transfer.shared.mapper;

import com.mcp.transfer.shared.entity.McpResource;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface McpResourceMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(McpResource record);

    int insertSelective(McpResource record);

    McpResource selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(McpResource record);

    int updateByPrimaryKey(McpResource record);

    /** 查询指定 server 下所有启用的 resource（status=1） */
    List<McpResource> selectAllByServerId(@Param("serverId") Integer serverId);

    /** 查询全部 resource（管理后台用） */
    List<McpResource> selectAll();

    /** 查询指定多个 server 下的 resource（管理后台数据隔离用） */
    List<McpResource> selectByServerIds(@Param("serverIds") List<Integer> serverIds);

    /** 按 server + uri 查询启用的 resource（resources/read 用） */
    McpResource selectOneByServerIdAndUri(@Param("serverId") Integer serverId, @Param("uri") String uri);

    /** 删除指定 server 下全部 resource（级联删除） */
    int deleteByServerId(@Param("serverId") Integer serverId);
}
