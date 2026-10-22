package com.mcp.transfer.mapper;

import com.mcp.transfer.entity.AdminUserTenant;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface AdminUserTenantMapper {

    List<Integer> selectTenantIdsByUserId(@Param("userId") Long userId);

    int insert(AdminUserTenant record);

    int deleteByUserId(@Param("userId") Long userId);

    int deleteByUserIdAndTenantId(@Param("userId") Long userId, @Param("tenantId") Integer tenantId);

    /** 删除某业务方下全部用户归属（删除业务方时清理） */
    int deleteByTenantId(@Param("tenantId") Integer tenantId);
}
