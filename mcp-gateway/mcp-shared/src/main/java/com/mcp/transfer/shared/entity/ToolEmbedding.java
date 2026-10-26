package com.mcp.transfer.shared.entity;

import lombok.Data;

import java.util.Date;

/**
 * 工具描述向量（embedding）。vector 存 JSON 数组，当前规模下内存余弦即可。
 */
@Data
public class ToolEmbedding {

    private Integer toolId;

    private Integer serverId;

    private String model;

    private Integer dim;

    private String vector;

    private Date updateTime;
}
