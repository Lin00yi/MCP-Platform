package com.mcp.transfer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 工具治理预设档位：用 {@code MCP_TOOLS_PROFILE=compact|large} 一次性调整一组高级参数。
 *
 * <p>优先级（从高到低）：
 * <ol>
 *   <li>命令行 / 环境变量单项覆盖（如 {@code MCP_TOOLS_PAGE_SIZE=120}）；</li>
 *   <li>{@code MCP_TOOLS_PROFILE} 预设；</li>
 *   <li>{@link com.mcp.transfer.exposure.ToolExposureProperties} 的 Java 默认值。</li>
 * </ol>
 *
 * <p>预设在 property source 末尾追加，因此不会覆盖显式环境变量；未设置或 {@code default}
 * 时不注入任何值，行为与历史默认完全一致。
 */
public class ToolProfileEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(ToolProfileEnvironmentPostProcessor.class);

    private static final String PROFILE_KEY = "MCP_TOOLS_PROFILE";

    private static final Map<String, Map<String, Object>> PRESETS = buildPresets();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String raw = environment.getProperty(PROFILE_KEY);
        if (!StringUtils.hasText(raw) && StringUtils.hasText(environment.getProperty("mcp.tools.profile"))) {
            raw = environment.getProperty("mcp.tools.profile");
        }
        if (!StringUtils.hasText(raw) || "default".equalsIgnoreCase(raw.trim())) {
            return;
        }
        String profile = raw.trim().toLowerCase(Locale.ROOT);
        Map<String, Object> preset = PRESETS.get(profile);
        if (preset == null) {
            logger.warn("[CONFIG] 未知 MCP_TOOLS_PROFILE={}，可选值：{}；按 default 处理",
                    raw, PRESETS.keySet());
            return;
        }
        // 单项显式覆盖优先于预设：环境变量（MCP_TOOLS_PAGE_SIZE）或 CLI/JSON 显式属性存在时不注入该键
        Map<String, Object> effective = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : preset.entrySet()) {
            if (environment.getProperty(entry.getKey()) == null && !hasEnvOverride(entry.getKey())) {
                effective.put(entry.getKey(), entry.getValue());
            }
        }
        if (effective.isEmpty()) {
            logger.info("[CONFIG] MCP_TOOLS_PROFILE={} 的所有键均已被显式覆盖，跳过预设", profile);
            return;
        }
        environment.getPropertySources().addLast(new MapPropertySource("mcpToolsProfile:" + profile, effective));
        logger.info("[CONFIG] 已应用工具治理预设 MCP_TOOLS_PROFILE={}（{} 项）", profile, effective.size());
    }

    /** 检查是否存在对应的环境变量单项覆盖，例如 mcp.tools.page-size -> MCP_TOOLS_PAGE_SIZE */
    private boolean hasEnvOverride(String propertyKey) {
        String envName = propertyKey.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
        String value = System.getenv(envName);
        return StringUtils.hasText(value);
    }

    private static Map<String, Map<String, Object>> buildPresets() {
        Map<String, Map<String, Object>> presets = new LinkedHashMap<>();

        // 小上下文：更小分页/预算/描述，适合轻量客户端或工具数多但长尾明显的场景
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("mcp.tools.page-size", 100);
        compact.put("mcp.tools.pagination-min-tools", 300);
        compact.put("mcp.tools.asset-pagination-min", 300);
        compact.put("mcp.tools.warn-threshold", 60);
        compact.put("mcp.tools.max-threshold", 200);
        compact.put("mcp.tools.search-limit", 8);
        compact.put("mcp.tools.max-search-limit", 30);
        compact.put("mcp.tools.hot-tool-limit", 30);
        compact.put("mcp.tools.max-description-length", 800);
        compact.put("mcp.tools.search-description-length", 200);
        compact.put("mcp.tools.result-spill-threshold", 3000);
        compact.put("mcp.tools.chunk-limit", 2000);
        presets.put("compact", Collections.unmodifiableMap(compact));

        // 大工具量：更大分页/预算，适合内网高配客户端；仍保留 META 应急开关
        Map<String, Object> large = new LinkedHashMap<>();
        large.put("mcp.tools.page-size", 200);
        large.put("mcp.tools.pagination-min-tools", 1000);
        large.put("mcp.tools.asset-pagination-min", 1000);
        large.put("mcp.tools.warn-threshold", 200);
        large.put("mcp.tools.max-threshold", 800);
        large.put("mcp.tools.search-limit", 15);
        large.put("mcp.tools.max-search-limit", 50);
        large.put("mcp.tools.hot-tool-limit", 50);
        large.put("mcp.tools.max-description-length", 1500);
        large.put("mcp.tools.search-description-length", 300);
        large.put("mcp.tools.result-spill-threshold", 6000);
        large.put("mcp.tools.chunk-limit", 4000);
        presets.put("large", Collections.unmodifiableMap(large));

        return Collections.unmodifiableMap(presets);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
