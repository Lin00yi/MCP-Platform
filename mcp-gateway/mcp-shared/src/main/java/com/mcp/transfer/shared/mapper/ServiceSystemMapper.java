package com.mcp.transfer.shared.mapper;

import com.mcp.transfer.shared.entity.ServiceSystem;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ServiceSystemMapper {
    ServiceSystem selectByPrimaryKey(Integer id);

    List<ServiceSystem> selectAllByStatus(@Param("status") Integer status);

    /** 查询全部系统服务（管理后台用） */
    List<ServiceSystem> selectAll();

    /** 选择性更新（管理后台用） */
    int updateByPrimaryKeySelective(ServiceSystem record);
}
