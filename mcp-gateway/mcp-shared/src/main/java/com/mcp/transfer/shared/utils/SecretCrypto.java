package com.mcp.transfer.shared.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 凭证加解密与脱敏工具。
 * <p>
 * - encrypt/decrypt：AES-256-GCM，密文带 {@code enc:v1:} 前缀；decrypt 对无前缀的明文原样返回（兼容历史数据）。
 * - maskBearer：脱敏 Authorization 头（Bearer <token> → Bearer 前3***后3）。
 * - sha256Hex：计算 SHA-256 十六进制（用于 sk 哈希联表，避免明文落库）。
 * <p>
 * 密钥来自配置 {@code mcp.security.secretkey}（Base64 的 32 字节）；未配置时使用开发默认口令，
 * 生产环境必须显式配置。
 */
@Component
public class SecretCrypto {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SecretCrypto.class);

    private static final String PREFIX = "enc:v1:";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LEN = 12;
    /** 开发默认口令（仅本地开发，生产务必通过 mcp.security.secretkey 覆盖） */
    private static final String DEV_KEY_PASSPHRASE = "mcp-gateway-dev-secret-2026-change-me";

    @Value("${mcp.security.secretkey:}")
    private String secretKeyBase64;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment environment;

    private SecretKeySpec keySpec;

    @PostConstruct
    public void init() {
        byte[] keyBytes;
        if (secretKeyBase64 != null && !secretKeyBase64.trim().isEmpty()) {
            try {
                keyBytes = Base64.getDecoder().decode(secretKeyBase64.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("mcp.security.secretkey 必须是 Base64 编码的 32 字节密钥", e);
            }
        } else {
            if (isProdProfile()) {
                throw new IllegalStateException(
                        "生产环境必须配置 MCP_SECURITY_SECRETKEY（mcp.security.secretkey，Base64 的 32 字节 AES-256 密钥）");
            }
            log.warn("[SECRET] 未配置 MCP_SECURITY_SECRETKEY，使用开发默认密钥（仅限本地/测试环境）");
            keyBytes = sha256(DEV_KEY_PASSPHRASE);
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException("mcp.security.secretkey 解码后必须为 32 字节（AES-256）");
        }
        this.keySpec = new SecretKeySpec(keyBytes, "AES");
    }

    private boolean isProdProfile() {
        if (environment == null) {
            return false;
        }
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    /** 加密明文；已加密或空值原样返回 */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty() || plaintext.startsWith(PREFIX)) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[IV_LEN + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, IV_LEN);
            System.arraycopy(ciphertext, 0, combined, IV_LEN, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("凭证加密失败", e);
        }
    }

    /** 解密；无前缀的历史明文原样返回 */
    public String decrypt(String value) {
        if (value == null || value.isEmpty() || !value.startsWith(PREFIX)) {
            return value;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(combined, 0, iv, 0, IV_LEN);
            byte[] ciphertext = new byte[combined.length - IV_LEN];
            System.arraycopy(combined, IV_LEN, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("凭证解密失败", e);
        }
    }

    /** 脱敏 Authorization 头：Bearer <token> → Bearer 前3***后3 */
    public String maskBearer(String authorization) {
        if (authorization == null || authorization.isEmpty()) {
            return authorization;
        }
        if (authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7);
            if (token.length() <= 8) {
                return "Bearer ***";
            }
            return "Bearer " + token.substring(0, 3) + "***" + token.substring(token.length() - 3);
        }
        return "***";
    }

    /** 提取 Authorization 中的 token（Bearer <token> → token），无则返回 null */
    public String extractBearerToken(String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }

    /** 计算 SHA-256 十六进制（小写），用于 sk 哈希联表 */
    public String sha256Hex(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        try {
            byte[] digest = sha256(input);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("sha256 计算失败", e);
        }
    }

    private byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 常见敏感字段名（JSON key），命中后值替换为 *** */
    private static final java.util.regex.Pattern SENSITIVE_JSON_FIELD = java.util.regex.Pattern.compile(
            "(?i)\"(token|secret|password|passwd|authorization|access_token|access-token|app_secret|api_key|apikey|sk)\"\\s*:\\s*\"[^\"]*\"");

    /**
     * 日志落库前脱敏：把 JSON 中常见的凭证字段值替换为 ***。
     * 只做“尽力而为”的脱敏，不能替代最小化日志原则。
     */
    public String maskJsonSecrets(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        try {
            return SENSITIVE_JSON_FIELD.matcher(json).replaceAll("\"$1\":\"***\"");
        } catch (Exception e) {
            return json;
        }
    }
}
