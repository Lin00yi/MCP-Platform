package com.mcp.transfer.exposure;

import com.mcp.transfer.entity.McpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具预算告警（A-lite：超阈值告警）。
 *
 * <p>同一 server 的同类告警按 {@code mcp.tools.warn-throttle-seconds} 节流，
 * 避免 tools/list 被频繁调用时刷爆日志；管理台仍会实时展示最新预算。
 */
@Component
public class ToolBudgetAlerter {

    private static final Logger logger = LoggerFactory.getLogger(ToolBudgetAlerter.class);

    private final Map<String, Long> lastWarnAt = new ConcurrentHashMap<>();

    @Autowired
    private ToolExposureProperties properties;

    public void check(McpServer server, ToolBudget budget) {
        if (server == null || budget == null) {
            return;
        }
        if (budget.isOverMax()) {
            warnOnce(server, "max", "工具数超过硬阈值：server={}(id={}) 可见 {} 个（阈值 {}），"
                            + "建议启用 META 暴露模式或拆分 server；当前仍按原模式返回。",
                    server.getName(), server.getId(), budget.getVisibleTools(), budget.getMaxThreshold());
        } else if (budget.isOverWarn()) {
            warnOnce(server, "warn", "工具数超过告警阈值：server={}(id={}) 可见 {} 个（阈值 {}），"
                            + "请评估是否启用白名单或 META 模式。",
                    server.getName(), server.getId(), budget.getVisibleTools(), budget.getWarnThreshold());
        }
        if (budget.isPromptOverWarn()) {
            warnOnce(server, "prompt", "Prompt 数超过告警阈值：server={}(id={}) 可见 {} 个（阈值 {}），"
                            + "请评估是否收紧白名单或拆分 server。",
                    server.getName(), server.getId(), budget.getVisiblePrompts(), budget.getPromptWarnThreshold());
        }
        if (budget.isResourceOverWarn()) {
            warnOnce(server, "resource", "Resource 数超过告警阈值：server={}(id={}) 可见 {} 个（阈值 {}），"
                            + "请评估是否使用 URI 模板、收紧白名单或拆分 server。",
                    server.getName(), server.getId(), budget.getVisibleResources(), budget.getResourceWarnThreshold());
        }
    }

    private void warnOnce(McpServer server, String level, String format, Object... args) {
        String key = server.getId() + ":" + level;
        long now = System.currentTimeMillis();
        long throttle = Math.max(0, properties.getWarnThrottleSeconds()) * 1000L;
        Long last = lastWarnAt.get(key);
        if (last != null && now - last < throttle) {
            return;
        }
        lastWarnAt.put(key, now);
        if ("max".equals(level)) {
            logger.error(format, args);
        } else {
            logger.warn(format, args);
        }
    }
}
