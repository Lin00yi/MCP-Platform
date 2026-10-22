package com.mcp.transfer.health;

/**
 * 单次探测结果。
 */
public class ProbeResult {
    /** 是否通过 */
    public boolean ok;
    /** 耗时（毫秒） */
    public long latencyMs;
    /** 说明 / 失败原因 */
    public String message;

    public ProbeResult(boolean ok, long latencyMs, String message) {
        this.ok = ok;
        this.latencyMs = latencyMs;
        this.message = message;
    }

    public static ProbeResult ok(long latencyMs, String message) {
        return new ProbeResult(true, latencyMs, message);
    }

    public static ProbeResult fail(long latencyMs, String message) {
        return new ProbeResult(false, latencyMs, message);
    }
}
