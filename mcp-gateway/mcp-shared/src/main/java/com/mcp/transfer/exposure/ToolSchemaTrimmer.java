package com.mcp.transfer.exposure;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Schema 瘦身（E 方案）：在不破坏参数校验语义的前提下，减少 tools/list 的 token 占用。
 *
 * <ul>
 *   <li>截断过长 description；</li>
 *   <li>移除 examples / $examples / input_examples（这些字段对模型调用不是必需，往往最占 token）；</li>
 *   <li>默认去掉每个工具重复的 server 元数据。</li>
 * </ul>
 */
@Component
public class ToolSchemaTrimmer {

    @Autowired
    private ToolExposureProperties properties;

    public com.mcp.transfer.shared.dto.mcp.result.tools.McpTool trim(com.mcp.transfer.shared.dto.mcp.result.tools.McpTool tool) {
        if (tool == null) {
            return null;
        }
        if (properties.getMaxDescriptionLength() > 0) {
            tool.setDescription(ToolDefinitionSupport.truncate(
                    tool.getDescription(), properties.getMaxDescriptionLength()));
        }
        if (properties.isTrimSchema() && tool.getInputSchema() != null) {
            stripExamples(tool.getInputSchema());
        }
        if (!properties.isIncludeServerMeta()) {
            tool.setServer(null);
        }
        if (tool.getTags() != null && tool.getTags().isEmpty()) {
            tool.setTags(null);
        }
        return tool;
    }

    /** 递归移除 schema 中的示例字段；保留 type/properties/required/enum 等校验关键字 */
    private void stripExamples(Object node) {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            obj.remove("examples");
            obj.remove("$examples");
            obj.remove("input_examples");
            for (Object value : obj.values()) {
                stripExamples(value);
            }
        } else if (node instanceof JSONArray) {
            for (Object item : (JSONArray) node) {
                stripExamples(item);
            }
        }
    }
}
