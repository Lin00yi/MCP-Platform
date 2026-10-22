package com.mcp.transfer.exposure;

import com.mcp.transfer.entity.McpServer;
import org.springframework.util.StringUtils;

/**
 * 资产访问 / 暴露模式常量与解析。
 *
 * <p>访问模式由 Tool / Prompt / Resource 共用同一枚举，三者在 Server 上各自独立配置：
 * <ul>
 *   <li>{@link #ACCESS_ALL}：该应用可见 server 下全部启用资产；</li>
 *   <li>{@link #ACCESS_WHITELIST}：只可见 app_*_rel 显式授权的资产，未配置=看不到。</li>
 * </ul>
 *
 * <p>暴露模式仅 Tool 使用，决定 tools/list 一次返回什么：
 * <ul>
 *   <li>{@link #EXPOSURE_DIRECT}：返回全部可见工具（可配合分页）；</li>
 *   <li>{@link #EXPOSURE_META}：只返回热工具 + 4 个元工具（search_tools/get_tool_schema/call_tool/read_result_chunk）。</li>
 * </ul>
 */
public final class ToolExposureModes {

    public static final String ACCESS_ALL = "ALL";
    public static final String ACCESS_WHITELIST = "WHITELIST";

    public static final String EXPOSURE_DIRECT = "DIRECT";
    public static final String EXPOSURE_META = "META";

    private ToolExposureModes() {
    }

    /** 解析 Tool 访问模式；空值/非法值按 ALL（应用授权已移除）。 */
    public static String accessOf(McpServer server) {
        if (server == null) {
            return ACCESS_WHITELIST;
        }
        return normalizeAccessMode(server.getToolAccessMode());
    }

    /** 解析暴露模式；空值/非法值按 DIRECT。 */
    public static String exposureOf(McpServer server) {
        if (server == null || !StringUtils.hasText(server.getToolExposureMode())) {
            return EXPOSURE_DIRECT;
        }
        String mode = server.getToolExposureMode().trim().toUpperCase();
        if (EXPOSURE_META.equals(mode)) {
            return EXPOSURE_META;
        }
        return EXPOSURE_DIRECT;
    }

    public static boolean isValidAccessMode(String mode) {
        if (!StringUtils.hasText(mode)) {
            return false;
        }
        String m = mode.trim().toUpperCase();
        return ACCESS_ALL.equals(m) || ACCESS_WHITELIST.equals(m);
    }

    public static boolean isValidExposureMode(String mode) {
        if (!StringUtils.hasText(mode)) {
            return false;
        }
        String m = mode.trim().toUpperCase();
        return EXPOSURE_DIRECT.equals(m) || EXPOSURE_META.equals(m);
    }

    /** 归一访问模式：WHITELIST / ALL，空值或非法值一律按 ALL（应用授权已移除）。 */
    public static String normalizeAccessMode(String mode) {
        if (!StringUtils.hasText(mode)) {
            return ACCESS_ALL;
        }
        String m = mode.trim().toUpperCase();
        return ACCESS_WHITELIST.equals(m) ? ACCESS_WHITELIST : ACCESS_ALL;
    }

    public static String normalizeExposureMode(String mode) {
        return StringUtils.hasText(mode) ? mode.trim().toUpperCase() : EXPOSURE_DIRECT;
    }

    /** Prompt 访问模式（与 Tool 同一枚举）。 */
    public static String promptAccessOf(McpServer server) {
        return assetAccessOf(server == null ? null : server.getPromptAccessMode());
    }

    /** Resource 访问模式（与 Tool 同一枚举）。 */
    public static String resourceAccessOf(McpServer server) {
        return assetAccessOf(server == null ? null : server.getResourceAccessMode());
    }

    private static String assetAccessOf(String value) {
        return normalizeAccessMode(value);
    }

    /** Prompt / Resource 与 Tool 使用同一套访问模式枚举。 */
    public static boolean isValidAssetAccessMode(String mode) {
        return isValidAccessMode(mode);
    }

    public static String normalizeAssetAccessMode(String mode) {
        return normalizeAccessMode(mode);
    }
}
