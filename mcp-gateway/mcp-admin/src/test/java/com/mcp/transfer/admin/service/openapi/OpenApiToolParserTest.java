package com.mcp.transfer.admin.service.openapi;

import com.alibaba.fastjson.JSON;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiToolParserTest {

    private static OpenApiToolDraft tool(OpenApiImportPlan plan, String code) {
        for (OpenApiToolDraft t : plan.getTools()) {
            if (code.equals(t.getCode())) {
                return t;
            }
        }
        throw new AssertionError("未找到工具 " + code + "，实际：" + plan.getTools().size());
    }

    private static boolean hasIssue(OpenApiToolDraft draft, String level, String keyword) {
        return draft.getIssues().stream()
                .anyMatch(i -> level.equals(i.getLevel()) && i.getMessage().contains(keyword));
    }

    private static boolean hasDocIssue(OpenApiImportPlan plan, String level, String keyword) {
        return plan.getIssues().stream()
                .anyMatch(i -> level.equals(i.getLevel()) && i.getMessage().contains(keyword));
    }

    @Test
    void parsesGetAndPostWithAllOfRefAndNullable() {
        String spec = "{"
                + "\"openapi\":\"3.0.3\","
                + "\"info\":{\"title\":\"Demo\",\"version\":\"2.3.4\"},"
                + "\"servers\":[{\"url\":\"https://api.example.com/v1/\"}],"
                + "\"paths\":{"
                + "  \"/users/{id}\":{\"get\":{"
                + "    \"operationId\":\"get_user\",\"summary\":\"查询用户\",\"description\":\"按 ID 查询\","
                + "    \"tags\":[\"user\",\"query\"],"
                + "    \"parameters\":["
                + "      {\"name\":\"id\",\"in\":\"path\",\"required\":true,\"schema\":{\"type\":\"string\"}},"
                + "      {\"name\":\"verbose\",\"in\":\"query\",\"schema\":{\"type\":\"boolean\"}},"
                + "      {\"name\":\"X-Trace\",\"in\":\"header\",\"required\":true,\"schema\":{\"type\":\"string\"}}"
                + "    ]}},"
                + "  \"/users\":{\"post\":{"
                + "    \"operationId\":\"create_user\",\"summary\":\"创建用户\","
                + "    \"requestBody\":{\"required\":true,\"content\":{\"application/json\":{\"schema\":{"
                + "      \"allOf\":["
                + "        {\"$ref\":\"#/components/schemas/UserBase\"},"
                + "        {\"type\":\"object\",\"properties\":{\"password\":{\"type\":\"string\",\"nullable\":true}},"
                + "         \"required\":[\"password\"]}"
                + "      ]}}}}}"
                + "  }"
                + "},"
                + "\"components\":{\"schemas\":{\"UserBase\":{\"type\":\"object\","
                + "  \"properties\":{\"name\":{\"type\":\"string\"},\"age\":{\"type\":\"integer\"}},"
                + "  \"required\":[\"name\"]}}}"
                + "}";

        OpenApiImportPlan plan = OpenApiToolParser.parse(spec, null, Collections.<String>emptySet());

        assertEquals("3.0.3", plan.getOpenapiVersion());
        assertEquals("https://api.example.com/v1", plan.getBaseUrl());
        assertEquals("Demo", plan.getSpecTitle());
        assertEquals(2, plan.getTotalOperations());
        assertEquals(2, plan.getReady());
        assertEquals(0, plan.getErrors());

        OpenApiToolDraft get = tool(plan, "get_user");
        assertEquals("GET", get.getMethod());
        assertEquals("https://api.example.com/v1/users/{id}", get.getServiceUrl());
        assertEquals("user,query", get.getTags());
        assertEquals("2.3.4", get.getVersion());
        assertEquals(OpenApiToolDraft.READY, get.getStatus());
        assertTrue(get.isImportable());

        com.alibaba.fastjson.JSONObject getMapping = JSON.parseObject(get.getParamMapping().toJSONString());
        assertEquals("{{input.id}}", getMapping.getJSONObject("path").getString("id"));
        assertEquals("{{input.verbose}}", getMapping.getJSONObject("query").getString("verbose"));
        assertEquals("{{input.X-Trace}}", getMapping.getJSONObject("header").getString("X-Trace"));

        com.alibaba.fastjson.JSONObject getSchema = JSON.parseObject(get.getInputSchema().toJSONString());
        assertEquals("object", getSchema.getString("type"));
        assertEquals("boolean", getSchema.getJSONObject("properties").getJSONObject("verbose").getString("type"));
        List<String> required = getSchema.getJSONArray("required").toJavaList(String.class);
        assertTrue(required.containsAll(Arrays.asList("id", "X-Trace")));
        assertFalse(required.contains("verbose"));

        OpenApiToolDraft post = tool(plan, "create_user");
        com.alibaba.fastjson.JSONObject postMapping = JSON.parseObject(post.getParamMapping().toJSONString());
        assertEquals("{{input.name}}", postMapping.getJSONObject("body").getString("name"));
        assertEquals("{{input.password}}", postMapping.getJSONObject("body").getString("password"));

        com.alibaba.fastjson.JSONObject postSchema = JSON.parseObject(post.getInputSchema().toJSONString());
        com.alibaba.fastjson.JSONObject postProps = postSchema.getJSONObject("properties");
        assertEquals("string", postProps.getJSONObject("name").getString("type"));
        assertEquals("integer", postProps.getJSONObject("age").getString("type"));
        // nullable → type 数组
        assertEquals(Arrays.asList("string", "null"),
                postProps.getJSONObject("password").getJSONArray("type").toJavaList(String.class));
        List<String> postRequired = postSchema.getJSONArray("required").toJavaList(String.class);
        assertTrue(postRequired.containsAll(Arrays.asList("name", "password")));
    }

    @Test
    void rejectsUnsupportedHttpMethods() {
        String spec = "{\"openapi\":\"3.0.0\",\"servers\":[{\"url\":\"https://api.example.com\"}],"
                + "\"paths\":{\"/users/{id}\":{\"put\":{\"operationId\":\"update_user\"}}}}";

        OpenApiImportPlan plan = OpenApiToolParser.parse(spec, null, Collections.<String>emptySet());
        OpenApiToolDraft draft = tool(plan, "update_user");

        assertEquals(OpenApiToolDraft.ERROR, draft.getStatus());
        assertFalse(draft.isImportable());
        assertTrue(hasIssue(draft, OpenApiIssue.ERROR, "仅支持 GET/POST"));
        assertEquals(1, plan.getErrors());
    }

    @Test
    void derivesCodeWhenOperationIdMissingAndDedupes() {
        String spec = "{\"openapi\":\"3.0.0\",\"servers\":[{\"url\":\"https://api.example.com\"}],"
                + "\"paths\":{"
                + "\"/orders/{orderId}\":{\"get\":{\"summary\":\"查询订单\"}},"
                + "\"/a\":{\"post\":{\"operationId\":\"dup\"}},"
                + "\"/b\":{\"post\":{\"operationId\":\"dup\"}}"
                + "}}";

        OpenApiImportPlan plan = OpenApiToolParser.parse(spec, null, Collections.<String>emptySet());
        assertEquals(3, plan.getTotalOperations());

        OpenApiToolDraft derived = tool(plan, "get_orders_orderId");
        assertTrue(hasIssue(derived, OpenApiIssue.WARN, "缺少 operationId"));
        assertEquals("查询订单", derived.getName());

        assertNotNull(tool(plan, "dup"));
        assertNotNull(tool(plan, "dup_2"));
        assertTrue(hasIssue(tool(plan, "dup_2"), OpenApiIssue.WARN, "工具标识重复"));
    }

    @Test
    void marksExistingCodesAsConflict() {
        String spec = "{\"openapi\":\"3.0.0\",\"servers\":[{\"url\":\"https://api.example.com\"}],"
                + "\"paths\":{\"/a\":{\"get\":{\"operationId\":\"exists\"}}}}";

        OpenApiImportPlan plan = OpenApiToolParser.parse(spec, null,
                new HashSet<>(Collections.singletonList("exists")));
        OpenApiToolDraft draft = tool(plan, "exists");

        assertEquals(OpenApiToolDraft.CONFLICT, draft.getStatus());
        assertFalse(draft.isImportable());
        assertEquals(1, plan.getConflicts());
        assertTrue(hasIssue(draft, OpenApiIssue.WARN, "已存在 code"));
    }

    @Test
    void rejectsSwagger2AndRelativeServerUrl() {
        OpenApiImportPlan swagger = OpenApiToolParser.parse(
                "{\"swagger\":\"2.0\",\"info\":{\"title\":\"x\"},\"paths\":{}}", null, null);
        assertTrue(hasDocIssue(swagger, OpenApiIssue.ERROR, "Swagger 2.0"));
        assertEquals(0, swagger.getTotalOperations());

        OpenApiImportPlan relative = OpenApiToolParser.parse(
                "{\"openapi\":\"3.0.0\",\"servers\":[{\"url\":\"/api\"}],\"paths\":{\"/a\":{\"get\":{}}}}",
                null, null);
        assertTrue(hasDocIssue(relative, OpenApiIssue.ERROR, "绝对地址"));
    }

    @Test
    void requiresServerUrlUnlessOverridden() {
        String spec = "{\"openapi\":\"3.0.0\",\"paths\":{\"/a\":{\"get\":{\"operationId\":\"a\"}}}}";

        OpenApiImportPlan noServer = OpenApiToolParser.parse(spec, null, null);
        assertTrue(hasDocIssue(noServer, OpenApiIssue.ERROR, "缺少 servers[0].url"));
        assertEquals(0, noServer.getTotalOperations());

        OpenApiImportPlan overridden = OpenApiToolParser.parse(spec, "https://gw.example.com/base/", null);
        assertEquals("https://gw.example.com/base", overridden.getBaseUrl());
        assertEquals("https://gw.example.com/base/a", tool(overridden, "a").getServiceUrl());
    }

    @Test
    void allowsFreeFormBodyWithoutOtherMappingsOnly() {
        String single = "{\"openapi\":\"3.0.0\",\"servers\":[{\"url\":\"https://api.example.com\"}],"
                + "\"paths\":{\"/free\":{\"post\":{\"operationId\":\"free\","
                + "\"requestBody\":{\"content\":{\"application/json\":{\"schema\":"
                + "{\"type\":\"object\",\"additionalProperties\":true}}}}}}}}";
        OpenApiImportPlan plan = OpenApiToolParser.parse(single, null, null);
        OpenApiToolDraft draft = tool(plan, "free");
        assertEquals(OpenApiToolDraft.READY, draft.getStatus());
        assertTrue(hasIssue(draft, OpenApiIssue.WARN, "自由格式"));
        assertFalse(draft.getParamMapping().containsKey("body"));

        String mixed = "{\"openapi\":\"3.0.0\",\"servers\":[{\"url\":\"https://api.example.com\"}],"
                + "\"paths\":{\"/free/{id}\":{\"post\":{\"operationId\":\"free2\","
                + "\"parameters\":[{\"name\":\"id\",\"in\":\"path\",\"required\":true,\"schema\":{\"type\":\"string\"}}],"
                + "\"requestBody\":{\"content\":{\"application/json\":{\"schema\":"
                + "{\"type\":\"object\",\"additionalProperties\":true}}}}}}}}";
        OpenApiToolDraft mixedDraft = tool(OpenApiToolParser.parse(mixed, null, null), "free2");
        assertEquals(OpenApiToolDraft.ERROR, mixedDraft.getStatus());
        assertTrue(hasIssue(mixedDraft, OpenApiIssue.ERROR, "自由格式"));
    }

    @Test
    void rejectsMultipartBodyAndExternalRef() {
        OpenApiImportPlan multipart = OpenApiToolParser.parse(
                "{\"openapi\":\"3.0.0\",\"servers\":[{\"url\":\"https://api.example.com\"}],"
                        + "\"paths\":{\"/upload\":{\"post\":{\"operationId\":\"upload\","
                        + "\"requestBody\":{\"content\":{\"multipart/form-data\":{\"schema\":{\"type\":\"object\"}}}}}}}}",
                null, null);
        assertTrue(hasIssue(tool(multipart, "upload"), OpenApiIssue.ERROR, "multipart/form-data"));

        OpenApiImportPlan external = OpenApiToolParser.parse(
                "{\"openapi\":\"3.0.0\",\"servers\":[{\"url\":\"https://api.example.com\"}],"
                        + "\"paths\":{\"/x\":{\"post\":{\"operationId\":\"x\","
                        + "\"requestBody\":{\"$ref\":\"other.json#/components/requestBodies/X\"}}}}}",
                null, null);
        assertTrue(hasIssue(tool(external, "x"), OpenApiIssue.ERROR, "外部 $ref"));
    }

    @Test
    void synthesizesUndeclaredPathVariablesAndWarnsOnCookieAndArrayQuery() {
        String spec = "{\"openapi\":\"3.0.0\",\"servers\":[{\"url\":\"https://api.example.com\"}],"
                + "\"paths\":{\"/items/{uid}/detail\":{\"get\":{\"operationId\":\"item_detail\","
                + "\"parameters\":["
                + "  {\"name\":\"ids\",\"in\":\"query\",\"schema\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},"
                + "  {\"name\":\"sid\",\"in\":\"cookie\",\"schema\":{\"type\":\"string\"}}"
                + "]}}}}";

        OpenApiToolDraft draft = tool(OpenApiToolParser.parse(spec, null, null), "item_detail");
        assertTrue(hasIssue(draft, OpenApiIssue.WARN, "路径占位符 {uid}"));
        assertEquals("{{input.uid}}", draft.getParamMapping().getJSONObject("path").getString("uid"));
        assertEquals("string", draft.getInputSchema().getJSONObject("properties").getJSONObject("uid").getString("type"));
        assertTrue(hasIssue(draft, OpenApiIssue.WARN, "序列化方式"));
        assertTrue(hasIssue(draft, OpenApiIssue.WARN, "cookie"));
        assertFalse(draft.getParamMapping().containsKey("header"));
    }

    @Test
    void mapsApiKeyHeaderAndBearerSecurity() {
        String apiKey = "{\"openapi\":\"3.0.0\",\"servers\":[{\"url\":\"https://api.example.com\"}],"
                + "\"security\":[{\"ApiKeyAuth\":[]}],"
                + "\"components\":{\"securitySchemes\":{\"ApiKeyAuth\":"
                + "{\"type\":\"apiKey\",\"in\":\"header\",\"name\":\"X-API-Key\"}}},"
                + "\"paths\":{\"/a\":{\"get\":{\"operationId\":\"a\"}}}}";
        OpenApiToolDraft keyed = tool(OpenApiToolParser.parse(apiKey, null, null), "a");
        assertEquals("API_KEY", keyed.getAuthType());
        assertEquals("X-API-Key", keyed.getAuthConfig().getString("header_name"));
        assertTrue(hasIssue(keyed, OpenApiIssue.WARN, "api_key"));

        String bearer = "{\"openapi\":\"3.0.0\",\"servers\":[{\"url\":\"https://api.example.com\"}],"
                + "\"security\":[{\"BearerAuth\":[]}],"
                + "\"components\":{\"securitySchemes\":{\"BearerAuth\":"
                + "{\"type\":\"http\",\"scheme\":\"bearer\"}}},"
                + "\"paths\":{\"/b\":{\"get\":{\"operationId\":\"b\"}}}}";
        OpenApiToolDraft token = tool(OpenApiToolParser.parse(bearer, null, null), "b");
        assertEquals("BEARER", token.getAuthType());
        assertTrue(hasIssue(token, OpenApiIssue.WARN, "token"));
    }

    @Test
    void reportsMalformedJsonAndEmptyPaths() {
        OpenApiImportPlan broken = OpenApiToolParser.parse("{oops", null, null);
        assertTrue(hasDocIssue(broken, OpenApiIssue.ERROR, "JSON 解析失败"));

        OpenApiImportPlan empty = OpenApiToolParser.parse(
                "{\"openapi\":\"3.0.0\",\"servers\":[{\"url\":\"https://api.example.com\"}],\"paths\":{}}",
                null, null);
        assertTrue(hasDocIssue(empty, OpenApiIssue.ERROR, "paths 为空"));
    }

    @Test
    void sanitizesIllegalCodeCharactersAndKeepsOrder() {
        String spec = "{\"openapi\":\"3.0.0\",\"servers\":[{\"url\":\"https://api.example.com\"}],"
                + "\"paths\":{"
                + "\"/a\":{\"get\":{\"operationId\":\"get.user by id!\"}},"
                + "\"/b\":{\"get\":{\"operationId\":\"tool\"}}"
                + "}}";
        OpenApiImportPlan plan = OpenApiToolParser.parse(spec, null, null);
        assertEquals(2, plan.getTotalOperations());
        // 顺序与文档一致（OrderedField）
        assertEquals("get_user_by_id", plan.getTools().get(0).getCode());
        assertEquals("tool", plan.getTools().get(1).getCode());
        assertEquals("get_user_by_id", tool(plan, "get_user_by_id").getCode());
    }
}
