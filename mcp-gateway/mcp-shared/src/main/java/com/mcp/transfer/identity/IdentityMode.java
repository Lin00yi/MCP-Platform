package com.mcp.transfer.identity;

import org.springframework.util.StringUtils;

/**
 * 内部身份断言的生效模式。
 *
 * <ul>
 *   <li>{@link #OFF}：不签发、不下传断言；</li>
 *   <li>{@link #OPTIONAL}：能签发就签发，缺少用户/客编或下游密钥配置时只告警并继续；</li>
 *   <li>{@link #REQUIRED}：必须签发，缺少用户/客编或签发失败时直接拒绝请求；</li>
 *   <li>{@link #INHERIT}：仅用于 Server / 聚合上游的覆盖值，表示沿用上层配置。</li>
 * </ul>
 */
public enum IdentityMode {

    OFF,
    OPTIONAL,
    REQUIRED,
    INHERIT;

    /** 解析配置值；空值或非法值返回 fallback */
    public static IdentityMode parse(String value, IdentityMode fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            return IdentityMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** 是否需要签发断言 */
    public boolean enabled() {
        return this == OPTIONAL || this == REQUIRED;
    }

    /** 是否允许在无法签发时静默降级 */
    public boolean optional() {
        return this == OPTIONAL;
    }
}
