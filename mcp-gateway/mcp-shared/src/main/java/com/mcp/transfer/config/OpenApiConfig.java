package com.mcp.transfer.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.PathParameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc OpenAPI 配置：单个 /v3/api-docs 同时包含「管理台」与「客户端」两类接口，用 tag 前缀区分。
 * - 管理台接口（/mcp/admin/**、/mcp/log/**）：由 @RestController 注解自动生成，tag 前缀；
 * - MCP 客户端接口（/mcp/e/{endpointKey}）：Streamable HTTP + JSON-RPC，程序化补充，tag 前缀。
 */
@Configuration
public class OpenApiConfig {

    private static final String AUTH_HEADER = "x-dsh-auth-token";
    private static final String SESSION_HEADER = "Mcp-Session-Id";

    @Bean
    public OpenAPI mcpGatewayOpenApi() {
        OpenAPI openAPI = new OpenAPI()
                .info(new Info()
                        .title("MCP Gateway 接口文档")
                        .version("1.0.0")
                        .description("同时包含控制面接口（管理端）与 MCP 客户端接口（数据面）。"))
                .addTagsItem(new Tag().name("MCP").description("MCP JSON-RPC 方法（initialize/tools/list/tools/call 等）"))
                .addTagsItem(new Tag().name("传输层").description("Streamable HTTP 的 SSE 流与终止会话"))
                .addTagsItem(new Tag().name("MCP 管理接口").description("业务方 / Server / 工具 / Prompt / Resource / Namespace / Endpoint / 审批 / 审计等"))
                .addTagsItem(new Tag().name("认证与用户").description("管理员登录与用户管理"))
                .addTagsItem(new Tag().name("调用日志").description("MCP 调用日志查询与导出"))
                .addTagsItem(new Tag().name("健康检测").description("Server 健康状态与手动探测"))
                .addTagsItem(new Tag().name("缓存刷新").description("控制面通知数据面刷新配置与工具缓存"))
                .addTagsItem(new Tag().name("MCP 发现").description("面向 MCP 客户端的资产发现（server / tool / prompt / resource）"))
                .addTagsItem(new Tag().name("发现预览").description("管理台预览当前登录用户视角的发现结果"));

        openAPI.addExtension("x-tagGroups", buildTagGroups());

        Paths paths = new Paths();
        paths.addPathItem("/mcp/e/{endpointKey}", buildMcpPath());
        openAPI.setPaths(paths);
        openAPI.setComponents(new io.swagger.v3.oas.models.Components()
                .addSchemas("JsonRpcRequest", buildJsonRpcRequestSchema())
                .addSchemas("JsonRpcResponse", buildJsonRpcResponseSchema()));
        return openAPI;
    }

    private PathItem buildMcpPath() {
        PathItem item = new PathItem();
        item.setPost(buildPost());
        item.setGet(buildGet());
        item.setDelete(buildDelete());
        return item;
    }

    private Operation buildPost() {
        Operation op = new Operation()
                .addTagsItem("MCP")
                .summary("MCP JSON-RPC 统一入口")
                .description("所有 MCP 方法（initialize/ping/notifications/initialized/tools/list/tools/call/prompts/list/prompts/get/resources/list/resources/read/resources/templates/list）都打到这个端点。\n"
                        + "握手：先 initialize（无需 session，响应头返回 Mcp-Session-Id），后续请求带上该头；通知（无 id）返回 202。\n"
                        + "鉴权：所有方法都要求 `x-dsh-auth-token`（ping、notifications/* 除外）。")
                .operationId("mcpJsonRpc");
        op.addParametersItem(new PathParameter().name("endpointKey").required(true)
                .description("Endpoint 的对外接入标识，例如 team-pcb")
                .schema(new Schema<String>().type("string").example("team-pcb")));
        op.addParametersItem(new HeaderParameter().name(AUTH_HEADER).required(false)
                .description("用户 token（`dsh_` 或 PAT）；ping / notifications/* 可免")
                .schema(new Schema<String>().type("string")));
        op.addParametersItem(new HeaderParameter().name(SESSION_HEADER).required(false)
                .description("initialize 响应头返回的会话 ID（除 initialize 外必填）")
                .schema(new Schema<String>().type("string")));

        MediaType mt = new MediaType()
                .schema(new Schema<>().$ref("#/components/schemas/JsonRpcRequest"))
                .addExamples("initialize", new Example().summary("初始化（返回 Mcp-Session-Id）")
                        .value(java.util.Map.of(
                                "jsonrpc", "2.0", "id", 1, "method", "initialize",
                                "params", java.util.Map.of("protocolVersion", "2024-11-05", "capabilities", java.util.Map.of(),
                                        "clientInfo", java.util.Map.of("name", "deep-agent", "version", "1.0")))))
                .addExamples("tools/list", new Example().summary("查询工具列表")
                        .value(java.util.Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list")))
                .addExamples("tools/call", new Example().summary("调用工具")
                        .value(java.util.Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/call",
                                "params", java.util.Map.of("name", "toolList", "arguments", java.util.Map.of()))))
                .addExamples("ping", new Example().summary("探活")
                        .value(java.util.Map.of("jsonrpc", "2.0", "id", 4, "method", "ping")));
        RequestBody rb = new RequestBody().required(true)
                .content(new Content().addMediaType("application/json", mt));
        op.setRequestBody(rb);

        MediaType respMt = new MediaType()
                .schema(new Schema<>().$ref("#/components/schemas/JsonRpcResponse"))
                .addExamples("initialize", new Example().summary("initialize 响应")
                        .value(java.util.Map.of("jsonrpc", "2.0", "id", 1, "result",
                                java.util.Map.of("protocolVersion", "2024-11-05",
                                        "capabilities", java.util.Map.of("tools", java.util.Map.of()),
                                        "serverInfo", java.util.Map.of("name", "team-pcb", "version", "1.0.0")))))
                .addExamples("toolsList", new Example().summary("tools/list 响应")
                        .value(java.util.Map.of("jsonrpc", "2.0", "id", 2, "result",
                                java.util.Map.of("tools", java.util.List.of(
                                        java.util.Map.of("name", "toolList", "description", "工具描述",
                                                "inputSchema", java.util.Map.of("type", "object", "properties", java.util.Map.of())))))));
        ApiResponses responses = new ApiResponses()
                .addApiResponse("200", new ApiResponse().description("JSON-RPC 响应")
                        .content(new Content().addMediaType("application/json", respMt)))
                .addApiResponse("202", new ApiResponse().description("通知已受理（无 id 的通知）"))
                .addApiResponse("400", new ApiResponse().description("缺少或无效的 Mcp-Session-Id"))
                .addApiResponse("401", new ApiResponse().description("缺少 / 无效 / 过期的 x-dsh-auth-token"))
                .addApiResponse("403", new ApiResponse().description("Endpoint 不存在或已停用"))
                .addApiResponse("503", new ApiResponse().description("用户 token 校验服务不可用（不是 token 失效）"));
        op.setResponses(responses);
        return op;
    }

    private Operation buildGet() {
        Operation op = new Operation()
                .addTagsItem("传输层")
                .summary("SSE 服务端推送流")
                .description("打开 server→client 的 SSE 流（text/event-stream 长连接），用于接收服务端主动推送。")
                .operationId("mcpSse");
        op.addParametersItem(new PathParameter().name("endpointKey").required(true)
                .schema(new Schema<String>().type("string").example("team-pcb")));
        op.addParametersItem(new HeaderParameter().name(AUTH_HEADER).required(false)
                .description("用户 token（`dsh_` 或 PAT）")
                .schema(new Schema<String>().type("string")));
        op.addParametersItem(new HeaderParameter().name(SESSION_HEADER).required(false)
                .schema(new Schema<String>().type("string")));
        op.setResponses(new ApiResponses()
                .addApiResponse("200", new ApiResponse().description(
                        "SSE 流（Content-Type: text/event-stream）"))
                .addApiResponse("400", new ApiResponse().description("Mcp-Session-Id 无效"))
                .addApiResponse("401", new ApiResponse().description("缺少 / 无效的 x-dsh-auth-token"))
                .addApiResponse("403", new ApiResponse().description("Endpoint 不存在或已停用"))
                .addApiResponse("429", new ApiResponse().description("SSE 连接数超限（全局或单用户）")));
        return op;
    }

    private Operation buildDelete() {
        Operation op = new Operation()
                .addTagsItem("传输层")
                .summary("终止会话")
                .description("结束会话并关闭对应 SSE 连接（含上游会话），返回 204。")
                .operationId("mcpTerminate");
        op.addParametersItem(new PathParameter().name("endpointKey").required(true)
                .schema(new Schema<String>().type("string").example("team-pcb")));
        op.addParametersItem(new HeaderParameter().name(AUTH_HEADER).required(false)
                .description("用户 token（`dsh_` 或 PAT）")
                .schema(new Schema<String>().type("string")));
        op.addParametersItem(new HeaderParameter().name(SESSION_HEADER).required(false)
                .description("待终止的会话 ID")
                .schema(new Schema<String>().type("string")));
        op.setResponses(new ApiResponses()
                .addApiResponse("204", new ApiResponse().description("会话已终止"))
                .addApiResponse("401", new ApiResponse().description("缺少 / 无效的 x-dsh-auth-token")));
        return op;
    }

    private Schema<?> buildJsonRpcRequestSchema() {
        Schema<String> jsonrpcSchema = new Schema<String>().type("string");
        jsonrpcSchema.setEnum(java.util.Arrays.asList("2.0"));
        Schema<String> methodSchema = new Schema<String>().type("string");
        methodSchema.setEnum(java.util.Arrays.asList("initialize", "ping", "notifications/initialized",
                "tools/list", "tools/call", "prompts/list", "prompts/get",
                "resources/list", "resources/read", "resources/templates/list"));
        return new Schema<>()
                .type("object")
                .addRequiredItem("jsonrpc").addRequiredItem("method")
                .addProperty("jsonrpc", jsonrpcSchema)
                .addProperty("id", new Schema<>().type("string").description("请求 ID；通知（如 notifications/initialized）不传"))
                .addProperty("method", methodSchema)
                .addProperty("params", new Schema<>().type("object").description("方法参数，见各方法示例"));
    }

    private Schema<?> buildJsonRpcResponseSchema() {
        Schema<String> jsonrpcSchema = new Schema<String>().type("string");
        jsonrpcSchema.setEnum(java.util.Arrays.asList("2.0"));
        return new Schema<>()
                .type("object")
                .addProperty("jsonrpc", jsonrpcSchema)
                .addProperty("id", new Schema<>().type("string"))
                .addProperty("result", new Schema<>().type("object").description("成功结果"))
                .addProperty("error", new Schema<>().type("object")
                        .addProperty("code", new Schema<Integer>().type("integer"))
                        .addProperty("message", new Schema<String>().type("string")));
    }

    /** x-tagGroups：把 tag 归到「客户端」「管理端」两个顶级目录 */
    private java.util.List<java.util.Map<String, Object>> buildTagGroups() {
        return java.util.Arrays.asList(
                tagGroup("客户端", "MCP", "传输层", "MCP 发现"),
                tagGroup("管理端", "MCP 管理接口", "认证与用户", "调用日志", "健康检测", "缓存刷新", "发现预览"));
    }

    private java.util.Map<String, Object> tagGroup(String name, String... tags) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", name);
        m.put("tags", java.util.Arrays.asList(tags));
        return m;
    }
}
