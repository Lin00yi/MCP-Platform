package com.mcp.transfer.exposure;

import com.mcp.transfer.shared.entity.McpServer;
import org.springframework.util.StringUtils;

/**
 * 资产暴露模式常量与解析。
 *
 * <p>应用授权已移除，资产可见性统一为「启用即可见」，因此不再有 WHITELIST/ALL 访问模式；
 * 仅保留 Tool 暴露模式：
 * <ul>
 *   <li>{@link #EXPOSURE_DIRECT}：返回全部可见工具（可配合分页）；</li>
 *   <li>{@link #EXPOSURE_META}：只返回热工具 + 4 个元工具（search_tools/get_tool_schema/call_tool/read_result_chunk）。</li>
 * </ul>
 *
 * <p>{@link #ACCESS_ALL} 仅作为历史字段的固定值保留（数据库列仍在，避免存量库迁移阻塞）。
 */
public final class ToolExposureModes {

    /** 历史访问模式字段的固定值：应用授权已移除，资产一律全部可见 */
    public static final String ACCESS_ALL = "ALL";

    public static final String EXPOSURE_DIRECT = "DIRECT";
    public static final String EXPOSURE_META = "META";

    private ToolExposureModes() {
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

    public static boolean isValidExposureMode(String mode) {
        if (!StringUtils.hasText(mode)) {
            return false;
        }
        String m = mode.trim().toUpperCase();
        return EXPOSURE_DIRECT.equals(m) || EXPOSURE_META.equals(m);
    }

    public static String normalizeExposureMode(String mode) {
        return StringUtils.hasText(mode) ? mode.trim().toUpperCase() : EXPOSURE_DIRECT;
    }
}
