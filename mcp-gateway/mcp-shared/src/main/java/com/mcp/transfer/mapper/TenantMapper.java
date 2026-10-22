package com.mcp.transfer.mapper;

import com.mcp.transfer.entity.Tenant;
import java.util.List;

public interface TenantMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(Tenant record);

    int insertSelective(Tenant record);

    Tenant selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(Tenant record);

    int updateByPrimaryKey(Tenant record);

    /** 查询全部业务方（管理后台用） */
    List<Tenant> selectAll();
}
