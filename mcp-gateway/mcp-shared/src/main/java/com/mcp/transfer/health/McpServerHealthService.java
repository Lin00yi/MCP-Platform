package com.mcp.transfer.health;

import com.mcp.transfer.shared.entity.McpServer;
import com.mcp.transfer.shared.mapper.ToolCallRecordMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Server 健康状态计算：
 * 组合「配置状态(status) + 主动探测结果(缓存 30s) + 近 N 分钟调用成功率」得出 5 态健康度。
 */
@Service
public class McpServerHealthService {

    /** 判定降级的最低样本量（少于该值的失败不参与判定，避免误报） */
    private static final long MIN_SAMPLE_FOR_RATE = 5L;

    @Value("${mcp.health.window-minutes:5}")
    private int windowMinutes;

    @Value("${mcp.health.degraded-rate:0.8}")
    private double degradedRate;

    /** 探测结果缓存：单副本进程内，多副本 Redis（一个副本探测，所有副本共读） */
    @Autowired
    private ProbeResultCache probeCache;

    @Autowired
    private McpServerHealthProbe probe;

    @Autowired
    private ToolCallRecordMapper toolCallRecordMapper;

    /** 单个 Server 健康详情 */
    public Map<String, Object> healthOf(McpServer server) {
        return healthOf(server, statsMap(), false);
    }

    /** 批量健康（只查一次调用统计，避免 N+1） */
    public List<Map<String, Object>> healthOfAll(List<McpServer> servers) {
        Map<Integer, Map<String, Object>> stats = statsMap();
        List<Map<String, Object>> out = new ArrayList<>();
        if (servers == null) return out;
        for (McpServer s : servers) {
            out.add(healthOf(s, stats, false));
        }
        return out;
    }

    /** 手动立即探测（跳过缓存） */
    public Map<String, Object> probeNow(McpServer server) {
        return healthOf(server, statsMap(), true);
    }

    private Map<String, Object> healthOf(McpServer s, Map<Integer, Map<String, Object>> stats, boolean force) {
        Map<String, Object> m = new HashMap<>();
        m.put("serverId", s.getId());
        m.put("name", s.getName());
        m.put("path", s.getPath());
        m.put("upstreamType", s.getUpstreamType() == null ? "NONE" : s.getUpstreamType());

        // 1) 已下线
        if (s.getStatus() == null || s.getStatus() != 1) {
            m.put("status", McpHealthStatus.DISABLED.name());
            m.put("ok", false);
            m.put("message", "已下线（status=0）");
            return m;
        }

        // 2) 主动探测（带缓存）
        ProbeSnapshot snap = force ? doProbe(s) : getOrProbe(s);
        ProbeResult pr = snap == null ? null : snap.result;
        if (pr != null) {
            m.put("latencyMs", pr.latencyMs);
            m.put("message", pr.message);
            m.put("checkedAt", new Date(snap.checkedAt));
        }

        // 3) 近 N 分钟调用统计
        Map<String, Object> st = stats.get(s.getId());
        long total = st == null ? 0 : toLong(st.get("total"));
        long okCount = st == null ? 0 : toLong(st.get("okCount"));
        double rate = total > 0 ? (double) okCount / total : -1;
        m.put("totalCalls", total);
        m.put("successRate", rate < 0 ? null : Math.round(rate * 1000) / 1000.0);

        // 4) 计算状态
        McpHealthStatus status;
        if (pr == null && total == 0) {
            status = McpHealthStatus.UNKNOWN;
        } else if (pr != null && !pr.ok) {
            status = McpHealthStatus.DOWN;
        } else if (total >= MIN_SAMPLE_FOR_RATE && rate >= 0 && rate < degradedRate) {
            status = McpHealthStatus.DEGRADED;
        } else if (pr != null && pr.ok) {
            status = McpHealthStatus.UP;
        } else {
            status = McpHealthStatus.UNKNOWN;
        }
        m.put("status", status.name());
        m.put("ok", status == McpHealthStatus.UP || status == McpHealthStatus.DEGRADED);
        return m;
    }

    private ProbeSnapshot getOrProbe(McpServer s) {
        ProbeSnapshot cached = probeCache.get(s.getId());
        if (cached != null) {
            return cached;
        }
        return doProbe(s);
    }

    private ProbeSnapshot doProbe(McpServer s) {
        ProbeResult result;
        try {
            result = probe.probe(s);
        } catch (Exception e) {
            result = ProbeResult.fail(0, "探测异常: " + e.getMessage());
        }
        ProbeSnapshot snapshot = new ProbeSnapshot(result, System.currentTimeMillis());
        probeCache.put(s.getId(), snapshot);
        return snapshot;
    }

    /** 近 N 分钟按 server 的调用统计：serverId -> {total, okCount, avgDuration, lastCall} */
    private Map<Integer, Map<String, Object>> statsMap() {
        Map<Integer, Map<String, Object>> map = new HashMap<>();
        try {
            Date since = new Date(System.currentTimeMillis() - windowMinutes * 60_000L);
            List<Map<String, Object>> rows = toolCallRecordMapper.selectServerStatsSince(since);
            if (rows != null) {
                for (Map<String, Object> r : rows) {
                    Object sid = r.get("serverId");
                    if (sid != null) {
                        map.put(((Number) sid).intValue(), r);
                    }
                }
            }
        } catch (Exception e) {
            // 统计失败不影响健康判定
        }
        return map;
    }

    private long toLong(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }
}
