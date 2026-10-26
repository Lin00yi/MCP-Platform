package com.mcp.transfer.exposure;

import com.alibaba.fastjson.JSON;
import com.mcp.transfer.shared.dto.mcp.result.tools.McpTool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * META 暴露模式的内置元工具定义。
 *
 * <p>tools/list 固定只暴露三件套 + read_result_chunk（外加少量热工具），
 * 把 O(N) 的工具定义压缩成 O(1)：
 * <ol>
 *   <li>{@code search_tools}：检索 / 按业务分组列目录（不返回 inputSchema）；</li>
 *   <li>{@code get_tool_schema}：按需取某个工具的参数定义；</li>
 *   <li>{@code call_tool}：按工具名执行，复用标准 tools/call 的鉴权、校验与日志；</li>
 *   <li>{@code read_result_chunk}：分段读取被截断暂存的超长结果（替代 Resources）。</li>
 * </ol>
 */
public final class MetaToolFactory {

    public static final String SEARCH_TOOLS = "search_tools";
    public static final String GET_TOOL_SCHEMA = "get_tool_schema";
    public static final String CALL_TOOL = "call_tool";
    public static final String READ_RESULT_CHUNK = "read_result_chunk";

    private static final Set<String> META_NAMES = new HashSet<>(Arrays.asList(
            SEARCH_TOOLS, GET_TOOL_SCHEMA, CALL_TOOL, READ_RESULT_CHUNK));

    private static final String SEARCH_SCHEMA = "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "\"query\":{\"type\":\"string\",\"description\":\"自然语言关键词，例如：订单查询、PCB 报价；留空则按业务分组列目录\"},"
            + "\"domain\":{\"type\":\"string\",\"description\":\"可选：限定业务分组（namespace 前缀）\"},"
            + "\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":\"可选：标签过滤，任一匹配\"},"
            + "\"server_id\":{\"type\":\"string\",\"description\":\"可选：限定 Server（serverKey 或 id）\"},"
            + "\"top_k\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":50,\"description\":\"返回条数，默认 10\"}"
            + "},"
            + "\"additionalProperties\":false"
            + "}";

    private static final String SCHEMA_SCHEMA = "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "\"name\":{\"type\":\"string\",\"description\":\"工具名（search_tools 返回的 name）\"},"
            + "\"tool_codes\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},"
            + "\"description\":\"批量获取多个工具的 Schema（最多 10 个）\"},"
            + "\"detail_level\":{\"type\":\"string\",\"enum\":[\"compact\",\"full\"],"
            + "\"description\":\"compact=精简版（默认，省 token）；full=完整版\"}"
            + "},"
            + "\"additionalProperties\":false"
            + "}";

    private static final String CALL_SCHEMA = "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "\"name\":{\"type\":\"string\",\"description\":\"要执行的工具名\"},"
            + "\"arguments\":{\"type\":\"object\",\"description\":\"参数对象，必须符合该工具的 inputSchema\",\"additionalProperties\":true},"
            + "\"reason\":{\"type\":\"string\",\"description\":\"可选：本次调用的原因，用于审批展示与审计\"}"
            + "},"
            + "\"required\":[\"name\"],"
            + "\"additionalProperties\":false"
            + "}";

    private static final String CHUNK_SCHEMA = "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "\"call_id\":{\"type\":\"string\",\"description\":\"超长结果返回的 call_id\"},"
            + "\"offset\":{\"type\":\"integer\",\"minimum\":0,\"description\":\"起始字符偏移，默认 0\"},"
            + "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":20000,\"description\":\"本次读取字符数，默认 3000\"}"
            + "},"
            + "\"required\":[\"call_id\"],"
            + "\"additionalProperties\":false"
            + "}";

    private MetaToolFactory() {
    }

    public static boolean isMetaTool(String name) {
        return name != null && META_NAMES.contains(name);
    }

    public static Set<String> names() {
        return META_NAMES;
    }

    /** 构造固定的元工具定义（每次返回新对象，避免被调用方修改后污染） */
    public static List<McpTool> build() {
        List<McpTool> tools = new ArrayList<>();
        tools.add(dto(SEARCH_TOOLS,
                "在当前 MCP 服务的可见工具中检索。工具很多时，先用本工具拿到候选 name，"
                        + "再用 get_tool_schema 查看参数，最后用 call_tool 执行。"
                        + "query 留空时按业务分组列出目录；返回 name、description、tags，不包含参数定义。",
                SEARCH_SCHEMA));
        tools.add(dto(GET_TOOL_SCHEMA,
                "获取指定工具的参数定义（inputSchema）。不确定参数名、类型或必填项时，先调用它。"
                        + "参数 name 来自 search_tools；可传 tool_codes 批量获取，detail_level=full 看完整版。",
                SCHEMA_SCHEMA));
        tools.add(dto(CALL_TOOL,
                "按工具名执行工具。name 来自 search_tools 或 get_tool_schema；"
                        + "arguments 必须符合该工具的 inputSchema。不能用于调用元工具本身。",
                CALL_SCHEMA));
        tools.add(dto(READ_RESULT_CHUNK,
                "分段读取被截断的超长工具结果。工具结果超过阈值时会在末尾返回 call_id，"
                        + "用本工具按 offset/limit 继续读取。",
                CHUNK_SCHEMA));
        return tools;
    }

    private static McpTool dto(String name, String description, String schemaJson) {
        McpTool tool = new McpTool();
        tool.setName(name);
        tool.setDescription(description);
        tool.setInputSchema(JSON.parseObject(schemaJson));
        tool.setTags(Arrays.asList("meta"));
        tool.setVersion("1.0.0");
        return tool;
    }
}
