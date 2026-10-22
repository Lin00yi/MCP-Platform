package com.mcp.transfer.port;

import java.util.List;
import java.util.Map;

/**
 * 健康查询端口（控制面 → 数据面）。
 *
 * <p>探活属于数据面：它才持有上游出网权限、SSRF 校验与探测结果缓存。
 * 控制面只做展示与租户过滤，不再自己打上游。
 */
public interface HealthQueryPort {

    /** 全部启用中的 Server 健康视图 */
    List<Map<String, Object>> listAll();

    /** 单个 Server 健康视图；不存在返回 null */
    Map<String, Object> one(Integer serverId);

    /** 立即探测（跳过缓存） */
    Map<String, Object> probe(Integer serverId);

    /** 数据面不可用 */
    class UnavailableException extends RuntimeException {
        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }

        public UnavailableException(String message) {
            super(message);
        }
    }
}
