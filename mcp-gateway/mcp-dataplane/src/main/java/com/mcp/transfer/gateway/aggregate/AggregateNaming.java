package com.mcp.transfer.gateway.aggregate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;

/**
 * 统一入口的工具 / Prompt 命名空间规则：{@code {serverKey}__{name}}。
 *
 * <p>serverKey 由 {@code ServerKeyUtils} 保证只含 {@code [A-Za-z0-9_-]}，
 * 但历史数据可能包含连续下划线，因此反向解析时按“最长前缀”匹配，
 * 不能简单按第一个 {@code __} 切分。
 *
 * <p><b>工具名长度保护</b>：主流大模型接口对工具名有 64 字符上限，而
 * {@code namespacePrefix__toolCode} 在长工具名、长前缀下可能超限，DSH 等客户端
 * 还会再叠加 {@code mcp__{serverName}__} 前缀。因此工具名统一走
 * {@link #exposeTool(String, String, int)}：超长时保留前缀与可读前段，追加
 * 短哈希，保证确定性且可反向解析。Prompt / Resource 不受该限制，继续用
 * {@link #expose(String, String)}。
 */
public final class AggregateNaming {

    public static final String SEPARATOR = "__";

    /** 对外工具名默认最大长度（含 namespace 前缀） */
    public static final int MAX_TOOL_NAME_LENGTH = 64;

    /** 短哈希长度（hex 字符） */
    private static final int HASH_LENGTH = 8;

    private AggregateNaming() {
    }

    /**
     * 不限制长度的暴露名（Prompt / Resource 用）。
     */
    public static String expose(String serverKey, String name) {
        return serverKey + SEPARATOR + name;
    }

    /**
     * 工具暴露名：超过 {@link #MAX_TOOL_NAME_LENGTH} 时截断 + 短哈希。
     */
    public static String exposeTool(String serverKey, String name) {
        return exposeTool(serverKey, name, MAX_TOOL_NAME_LENGTH);
    }

    /**
     * 工具暴露名（可指定上限，便于配置与单测）。
     *
     * @param maxLength 最大长度；<=0 表示不限制
     */
    public static String exposeTool(String serverKey, String name, int maxLength) {
        String full = expose(serverKey, name);
        if (maxLength <= 0 || full.length() <= maxLength) {
            return full;
        }
        String head = serverKey + SEPARATOR;
        String hash = shortHash(name);
        // 保留分隔符 "_" + 短哈希
        int keep = maxLength - head.length() - HASH_LENGTH - 1;
        if (keep <= 0) {
            // 前缀本身已接近上限：只保留哈希，保证结果仍以 serverKey__ 开头
            String fallback = head + hash;
            return fallback.length() <= maxLength ? fallback : fallback.substring(0, maxLength);
        }
        String code = name.length() <= keep ? name : name.substring(0, keep);
        String fitted = head + code + "_" + hash;
        return fitted.length() <= maxLength ? fitted : fitted.substring(0, maxLength);
    }

    private static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(HASH_LENGTH);
            for (int i = 0; i < bytes.length && sb.length() < HASH_LENGTH; i++) {
                sb.append(Character.forDigit((bytes[i] >> 4) & 0x0F, 16));
                if (sb.length() < HASH_LENGTH) {
                    sb.append(Character.forDigit(bytes[i] & 0x0F, 16));
                }
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 必然存在；退化为稳定哈希，保证不阻断请求
            return String.format("%08x", String.valueOf(value).hashCode());
        }
    }

    public static boolean hasPrefix(String exposedName, String serverKey) {
        return exposedName != null && serverKey != null
                && exposedName.length() > serverKey.length() + SEPARATOR.length()
                && exposedName.startsWith(serverKey + SEPARATOR);
    }

    /**
     * 从完整名字中剥离最长的 serverKey 前缀。
     *
     * @return 解析成功返回 {@link Resolved}；没有匹配返回 null
     */
    public static Resolved resolve(String exposedName, Collection<String> serverKeys) {
        if (exposedName == null || serverKeys == null || serverKeys.isEmpty()) {
            return null;
        }
        String best = null;
        for (String key : serverKeys) {
            if (key == null || key.isEmpty()) {
                continue;
            }
            if (hasPrefix(exposedName, key) && (best == null || key.length() > best.length())) {
                best = key;
            }
        }
        if (best == null) {
            return null;
        }
        return new Resolved(best, exposedName.substring(best.length() + SEPARATOR.length()));
    }

    public static final class Resolved {
        private final String serverKey;
        private final String name;

        public Resolved(String serverKey, String name) {
            this.serverKey = serverKey;
            this.name = name;
        }

        public String getServerKey() {
            return serverKey;
        }

        public String getName() {
            return name;
        }
    }
}
