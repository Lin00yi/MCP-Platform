package com.mcp.transfer.shared.entity;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;
import lombok.experimental.Accessors;

/** 命名空间成员关系。 */
@Data
@Accessors(chain = true)
public class McpNamespaceServer implements Serializable {
    private static final long serialVersionUID = 1L;

    private Integer id;
    private Integer namespaceId;
    private Integer serverId;
    private Date createTime;
}
