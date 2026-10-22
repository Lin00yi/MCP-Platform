package com.mcp.transfer.exposure;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolSchemaCompactorTest {

    @Test
    void removesDisplayFieldsButKeepsValidationKeywords() {
        JSONObject schema = JSON.parseObject("{"
                + "\"type\":\"object\","
                + "\"title\":\"Order\","
                + "\"description\":\"order request\","
                + "\"$schema\":\"http://json-schema.org/draft-07/schema#\","
                + "\"properties\":{"
                + "\"orderNo\":{\"type\":\"string\",\"description\":\"订单号\",\"examples\":[\"A1\"],\"minLength\":1},"
                + "\"status\":{\"type\":\"string\",\"enum\":[\"PAID\",\"REFUND\"]}"
                + "},"
                + "\"required\":[\"orderNo\"],"
                + "\"additionalProperties\":false"
                + "}");

        JSONObject compact = ToolSchemaCompactor.compact(schema);

        assertEquals("object", compact.getString("type"));
        assertFalse(compact.containsKey("title"));
        assertFalse(compact.containsKey("description"));
        assertFalse(compact.containsKey("$schema"));
        assertEquals("orderNo", compact.getJSONArray("required").getString(0));
        assertEquals(Boolean.FALSE, compact.getBoolean("additionalProperties"));

        JSONObject orderNo = compact.getJSONObject("properties").getJSONObject("orderNo");
        assertFalse(orderNo.containsKey("description"));
        assertFalse(orderNo.containsKey("examples"));
        assertEquals(Integer.valueOf(1), orderNo.getInteger("minLength"));
        assertEquals("string", orderNo.getString("type"));

        JSONObject status = compact.getJSONObject("properties").getJSONObject("status");
        assertEquals(2, status.getJSONArray("enum").size());
    }

    @Test
    void compactsNestedItemsAndCombinators() {
        JSONObject schema = JSON.parseObject("{"
                + "\"type\":\"object\","
                + "\"properties\":{"
                + "\"items\":{\"type\":\"array\",\"items\":{\"type\":\"object\","
                + "\"description\":\"line\",\"properties\":{\"sku\":{\"type\":\"string\",\"title\":\"SKU\"}}}},"
                + "\"amount\":{\"oneOf\":[{\"type\":\"number\",\"description\":\"numeric\"},"
                + "{\"type\":\"string\",\"description\":\"text\"}]}"
                + "}"
                + "}");

        JSONObject compact = ToolSchemaCompactor.compact(schema);
        JSONObject items = compact.getJSONObject("properties").getJSONObject("items");
        assertFalse(items.getJSONObject("items").containsKey("description"));
        assertFalse(items.getJSONObject("items").getJSONObject("properties")
                .getJSONObject("sku").containsKey("title"));
        JSONObject oneOf = compact.getJSONObject("properties").getJSONObject("amount")
                .getJSONArray("oneOf").getJSONObject(0);
        assertFalse(oneOf.containsKey("description"));
        assertEquals("number", oneOf.getString("type"));
    }

    @Test
    void nullSchemaBecomesEmptyObject() {
        JSONObject compact = ToolSchemaCompactor.compact(null);
        assertNotNull(compact);
        assertTrue(compact.isEmpty());
    }
}
