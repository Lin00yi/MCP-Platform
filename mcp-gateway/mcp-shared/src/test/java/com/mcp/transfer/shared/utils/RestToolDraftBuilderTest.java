package com.mcp.transfer.shared.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestToolDraftBuilderTest {

    private static Map<String, Object> draft(String url, String method, String bodyExample) {
        return RestToolDraftBuilder.buildDraft("查询客户", "query-customer", "查询客户信息",
                method, url, bodyExample, "NONE");
    }

    private static JSONObject resJson(Map<String, Object> draft) {
        return JSON.parseObject((String) draft.get("resJson"));
    }

    private static JSONObject inputSchema(Map<String, Object> draft) {
        return resJson(draft).getJSONObject("inputSchema");
    }

    private static JSONObject properties(Map<String, Object> draft) {
        return inputSchema(draft).getJSONObject("properties");
    }

    private static JSONObject paramMapping(Map<String, Object> draft) {
        return JSON.parseObject((String) draft.get("paramMapping"));
    }

    @Test
    void stripsQueryPlaceholdersFromServiceUrl() {
        Map<String, Object> draft = draft(
                "https://api.example.com/users/{id}?region={region}&lang=zh&debug", "GET", null);

        // query 占位符必须从 serviceUrl 剥离，静态参数（含有值/无值）原样保留
        assertEquals("https://api.example.com/users/{id}?lang=zh&debug", draft.get("serviceUrl"));
        assertEquals("GET", draft.get("requestType"));
        assertEquals("NONE", draft.get("authType"));

        JSONObject mapping = paramMapping(draft);
        assertEquals("{{input.id}}", mapping.getJSONObject("path").getString("id"));
        assertEquals("{{input.region}}", mapping.getJSONObject("query").getString("region"));
        assertEquals(1, mapping.getJSONObject("query").size());

        JSONObject props = properties(draft);
        assertEquals("string", props.getJSONObject("id").getString("type"));
        assertEquals("string", props.getJSONObject("region").getString("type"));

        // path / query 占位符是显式入参 → required
        assertEquals(Arrays.asList("id", "region"), inputSchema(draft).getJSONArray("required").toJavaList(String.class));
        assertFalse(draft.get("serviceUrl").toString().contains("{region}"));
    }

    @Test
    void dropsQuestionMarkWhenOnlyPlaceholderQueryRemains() {
        Map<String, Object> draft = draft("https://api.example.com/search?q={keyword}", "GET", null);

        assertEquals("https://api.example.com/search", draft.get("serviceUrl"));
        assertEquals("{{input.keyword}}", paramMapping(draft).getJSONObject("query").getString("q"));
        assertEquals(Arrays.asList("keyword"),
                inputSchema(draft).getJSONArray("required").toJavaList(String.class));
    }

    @Test
    void keepsStaticQueryUntouchedAndOmitsRequired() {
        Map<String, Object> draft = draft("https://api.example.com/list?page=1&size=20", "GET", null);

        assertEquals("https://api.example.com/list?page=1&size=20", draft.get("serviceUrl"));
        assertEquals("{}", draft.get("paramMapping"));
        assertEquals(0, properties(draft).size());
        assertFalse(inputSchema(draft).containsKey("required"));
    }

    @Test
    void infersNestedBodySchemaButKeepsBodyOptional() {
        String body = "{\"name\":\"张三\",\"age\":18,\"score\":9.5,\"vip\":true,"
                + "\"tags\":[\"a\",\"b\"],\"plainArray\":[1,2],\"emptyArray\":[],"
                + "\"meta\":{\"city\":\"北京\",\"geo\":{\"lat\":39.9}},\"note\":null}";
        Map<String, Object> draft = draft("https://api.example.com/users/{id}", "POST", body);

        JSONObject props = properties(draft);
        assertEquals("string", props.getJSONObject("name").getString("type"));
        assertEquals("integer", props.getJSONObject("age").getString("type"));
        assertEquals("number", props.getJSONObject("score").getString("type"));
        assertEquals("boolean", props.getJSONObject("vip").getString("type"));

        JSONObject tags = props.getJSONObject("tags");
        assertEquals("array", tags.getString("type"));
        assertEquals("string", tags.getJSONObject("items").getString("type"));

        assertEquals("integer", props.getJSONObject("plainArray").getJSONObject("items").getString("type"));
        assertEquals("array", props.getJSONObject("emptyArray").getString("type"));
        assertTrue(props.getJSONObject("emptyArray").getJSONObject("items").isEmpty());

        JSONObject meta = props.getJSONObject("meta");
        assertEquals("object", meta.getString("type"));
        assertEquals("string", meta.getJSONObject("properties").getJSONObject("city").getString("type"));
        JSONObject geo = meta.getJSONObject("properties").getJSONObject("geo");
        assertEquals("object", geo.getString("type"));
        assertEquals("number", geo.getJSONObject("properties").getJSONObject("lat").getString("type"));

        // 示例值为 null → 不限定类型
        assertFalse(props.getJSONObject("note").containsKey("type"));

        // body 字段映射到 input.<字段>，但 body 示例字段不进 required
        JSONObject bodyMapping = paramMapping(draft).getJSONObject("body");
        assertEquals("{{input.name}}", bodyMapping.getString("name"));
        assertEquals("{{input.meta}}", bodyMapping.getString("meta"));
        assertEquals(Arrays.asList("id"), inputSchema(draft).getJSONArray("required").toJavaList(String.class));
    }

    @Test
    void dedupesRepeatedPathPlaceholders() {
        Map<String, Object> draft = draft("https://api.example.com/{id}/detail/{id}", "GET", null);

        List<String> required = inputSchema(draft).getJSONArray("required").toJavaList(String.class);
        assertEquals(Arrays.asList("id"), required);
        assertEquals(1, paramMapping(draft).getJSONObject("path").size());
        assertEquals(1, properties(draft).size());
    }

    @Test
    void ignoresInvalidBodyExample() {
        Map<String, Object> draft = draft("https://api.example.com/users", "POST", "{oops");

        assertNotNull(draft.get("resJson"));
        assertEquals(0, properties(draft).size());
        assertFalse(paramMapping(draft).containsKey("body"));
    }

    @Test
    void defaultsMethodAndAuthType() {
        Map<String, Object> draft = RestToolDraftBuilder.buildDraft(null, null, null, null,
                "https://api.example.com/ping", null, null);

        assertEquals("GET", draft.get("requestType"));
        assertEquals("NONE", draft.get("authType"));
        assertEquals("https://api.example.com/ping", draft.get("serviceUrl"));
        assertNotNull(resJson(draft).getJSONObject("inputSchema"));
    }

    @Test
    void generatedSchemaIsValidDraft07AndEnforcesRequired() throws Exception {
        Map<String, Object> draft = draft("https://api.example.com/users/{id}?region={region}", "GET", null);
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
                .getSchema(new ObjectMapper().readTree((String) draft.get("resJson")).get("inputSchema"));
        ObjectMapper mapper = new ObjectMapper();

        // 缺 path/query 必填入参 → 校验报错
        Set<ValidationMessage> missing = schema.validate(mapper.readTree("{}"));
        assertEquals(2, missing.size());

        // 必填齐全 → 通过（body 示例字段不进 required，可缺省）
        Set<ValidationMessage> ok = schema.validate(mapper.readTree("{\"id\":\"u1\",\"region\":\"cn\"}"));
        assertTrue(ok.isEmpty(), ok.toString());
    }

    @Test
    void rejectsBlankUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> RestToolDraftBuilder.buildDraft("n", "c", "d", "GET", "  ", null, null));
    }

    @Test
    void ignoresArrayBodyAndKeepsPathParams() {
        // 数组 body 暂不支持（运行时 body 映射不会剥 jsonArr 壳）：不生成 body 映射，properties 只保留占位符
        Map<String, Object> draft = draft("https://api.example.com/batch/{id}", "POST", "[1,2,3]");

        assertEquals(1, properties(draft).size());
        assertNull(paramMapping(draft).get("body"));
    }
}
