package com.mcp.transfer.entity;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;
import lombok.experimental.Accessors;

/** 接入点与命名空间的绑定关系。 */
@Data
@Accessors(chain = true)
public class McpEndpointNamespace implements Serializable {
    private static final long serialVersionUID = 1L;

    private Integer id;
    private Integer endpointId;
    private Integer namespaceId;
    private Date createTime;
}
