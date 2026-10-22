package com.mcp.transfer.entity;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;
import lombok.experimental.Accessors;

/** MCP 命名空间：一组 Server 的逻辑分组，带稳定工具名前缀。 */
@Data
@Accessors(chain = true)
public class McpNamespace implements Serializable {
    private static final long serialVersionUID = 1L;

    private Integer id;
    private String name;
    /** 工具名前缀，创建后不可变 */
    private String prefix;
    private String description;
    /** 1启用 0停用 */
    private Integer status;
    private Date createTime;
    private Date updateTime;
}
