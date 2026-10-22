package com.mcp.transfer.utils;

import org.slf4j.MDC;
import java.util.UUID;

/**
 * TraceId 工具类：生成、存储、获取链路ID
 */
public class TraceIdUtils {

    // ThreadLocal 存储当前线程的 traceId
    private static final ThreadLocal<String> TRACE_ID_THREAD_LOCAL = new ThreadLocal<>();
    // 日志 MDC 的 key（需和日志配置中的 %X{traceId} 对应）
    public static final String TRACE_ID_KEY = "traceId";
    // 响应头中的 traceId 字段名
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /**
     * 生成唯一 traceId（UUID 去掉横线）
     */
    public static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 设置 traceId 到 ThreadLocal 和 MDC
     */
    public static void setTraceId(String traceId) {
        TRACE_ID_THREAD_LOCAL.set(traceId);
        MDC.put(TRACE_ID_KEY, traceId);
    }

    /**
     * 获取当前线程的 traceId
     */
    public static String getTraceId() {
        String traceId = TRACE_ID_THREAD_LOCAL.get();
        return traceId == null ? "" : traceId;
    }

    /**
     * 清除 ThreadLocal 和 MDC 中的 traceId（防止内存泄漏）
     */
    public static void clear() {
        TRACE_ID_THREAD_LOCAL.remove();
        MDC.remove(TRACE_ID_KEY);
    }
}