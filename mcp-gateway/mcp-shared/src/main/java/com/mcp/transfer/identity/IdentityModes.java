package com.mcp.transfer.identity;

import org.springframework.util.StringUtils;

/**
 * 身份下传模式的统一解析：全局开关 + 全局模式 + Server 覆盖。
 *
 * <p>优先级：
 * <ol>
 *   <li>{@code mcp.identity.enabled=false} → 一律 {@link IdentityMode#OFF}；</li>
 *   <li>Server / 聚合上游显式配置且不是 {@code INHERIT} → 用覆盖值；</li>
 *   <li>否则用全局 {@code mcp.identity.mode}（缺省 OPTIONAL）。</li>
 * </ol>
 */
public final class IdentityModes {

    private IdentityModes() {
    }

    public static IdentityMode effective(IdentityProperties properties, String serverMode) {
        if (properties == null || !properties.isEnabled()) {
            return IdentityMode.OFF;
        }
        IdentityMode perServer = IdentityMode.parse(serverMode, IdentityMode.INHERIT);
        if (perServer != IdentityMode.INHERIT) {
            return perServer;
        }
        IdentityMode global = IdentityMode.parse(properties.getMode(), IdentityMode.OPTIONAL);
        return global == IdentityMode.INHERIT ? IdentityMode.OPTIONAL : global;
    }

    /** 规范化 Server 上保存的覆盖值：空值统一为 INHERIT */
    public static String normalizeServerMode(String value) {
        return IdentityMode.parse(value, IdentityMode.INHERIT).name();
    }

    public static String effectiveName(IdentityProperties properties, String serverMode) {
        return effective(properties, serverMode).name();
    }

    public static boolean isValidServerMode(String value) {
        if (!StringUtils.hasText(value)) {
            return true;
        }
        IdentityMode mode = IdentityMode.parse(value, null);
        return mode != null;
    }
}
