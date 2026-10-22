package com.mcp.transfer.dto;

import lombok.Data;

import java.util.List;

/**
 * 批量把工具从一个 Server 迁移到另一个 Server（按业务域拆分 Server 的辅助接口）。
 */
@Data
public class BulkMoveToolsRequest {

    private List<Integer> toolIds;

    private Integer targetServerId;

    /** 迁移后是否把目标 Server 授权给原 Server 已授权的应用，并补齐被迁移工具的授权 */
    private Boolean grantToApps;
}
