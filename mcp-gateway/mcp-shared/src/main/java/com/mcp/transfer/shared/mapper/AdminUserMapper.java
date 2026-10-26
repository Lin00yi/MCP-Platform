package com.mcp.transfer.shared.mapper;

import com.mcp.transfer.shared.entity.AdminUser;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface AdminUserMapper {
    AdminUser selectByPrimaryKey(Long id);

    AdminUser selectByUsername(@Param("username") String username);

    List<AdminUser> selectAll();

    int insertSelective(AdminUser record);

    int updateByPrimaryKeySelective(AdminUser record);

    int deleteByPrimaryKey(Long id);
}
