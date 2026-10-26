package com.mcp.transfer.shared.entity;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;
import lombok.experimental.Accessors;

/** MCP 对外接入点：/mcp/e/{endpointKey}，绑定若干命名空间。 */
@Data
@Accessors(chain = true)
public class McpEndpoint implements Serializable {
    private static final long serialVersionUID = 1L;

    private Integer id;
    /** 归属业务方；null=平台级（平台超管可跨租户聚合） */
    private Integer tenantId;
    /** 对外路径标识，创建后不可变 */
    private String endpointKey;
    private String name;
    private String description;
    /** 1启用 0停用 */
    private Integer status;
    private Date createTime;
    private Date updateTime;
}
