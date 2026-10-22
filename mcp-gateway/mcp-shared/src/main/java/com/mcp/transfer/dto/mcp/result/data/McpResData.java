package com.mcp.transfer.dto.mcp.result.data;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class McpResData {

    private List<JSONObject> content;

    /**
     * MCP 工具级错误标记：参数校验失败、工具不存在/未授权、元工具调用失败等
     * 以 isError=true 的 content 返回，让模型能读到可修正的错误信息。
     */
    @JSONField(name = "isError")
    @JsonProperty("isError")
    private Boolean isError;
}
