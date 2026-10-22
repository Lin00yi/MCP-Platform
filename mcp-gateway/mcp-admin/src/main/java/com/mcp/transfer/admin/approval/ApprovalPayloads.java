package com.mcp.transfer.admin.approval;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 审批快照的展示脱敏。
 *
 * <p>快照落库前由 {@code SecretCrypto} 整体加密；查询详情时解密后，
 * 用本类把凭证类字段（含嵌套在 authConfig 字符串里的凭证）替换为 {@code ***}，
 * 避免审批页面泄露明文密钥。
 */
public final class ApprovalPayloads {

    private ApprovalPayloads() {
    }

    private static final Set<String> SENSITIVE_KEYS = new HashSet<>(Arrays.asList(
            "authconfig", "appsecret", "app_secret", "password", "passwd",
            "token", "accesstoken", "access_token", "secret", "apikey", "api_key",
            "authorization", "key"
    ));

    /** 展示用脱敏：解析 JSON 后按字段名替换为 ***，保证输出仍是合法 JSON */
    public static String mask(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        try {
            Object parsed = JSON.parse(json);
            maskNode(parsed);
            return JSON.toJSONString(parsed);
        } catch (Exception e) {
            // 非 JSON 内容不展示原文
            return "***";
        }
    }

    private static void maskNode(Object node) {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            for (String key : new ArrayList<>(obj.keySet())) {
                if (SENSITIVE_KEYS.contains(key.toLowerCase())) {
                    obj.put(key, "***");
                } else {
                    maskNode(obj.get(key));
                }
            }
        } else if (node instanceof JSONArray) {
            for (Object item : (JSONArray) node) {
                maskNode(item);
            }
        }
    }
}
