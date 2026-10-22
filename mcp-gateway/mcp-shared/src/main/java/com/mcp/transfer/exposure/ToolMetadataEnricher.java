package com.mcp.transfer.exposure;

import com.mcp.transfer.entity.McpTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 离线元数据增强（E1 的确定性基线）。
 *
 * <p>原始工具常见问题：英文 code/name 晦涩、缺中文说明、没有标签。
 * 这里在检索前做一次零外部依赖的补齐：
 * <ol>
 *   <li>把 {@code order_query} / {@code orderQuery} 拆成可检索的英文词；</li>
 *   <li>通过 {@link ToolSynonyms} 反向补出中文词（order → 订单）；</li>
 *   <li>把补出的词并入 description 与 tags，参与 BM25/关键词/向量检索。</li>
 * </ol>
 *
 * <p>增强结果不落库、幂等，可通过 {@code mcp.tools.metadata-enrich=false} 关闭。
 * 后续接入 LLM 离线增强时，替换本类的 {@link #generatedTerms} 即可。
 */
@Component
public class ToolMetadataEnricher {

    @Autowired
    private ToolExposureProperties properties;

    /** 增强后的描述（原始描述 + 缺失的检索词）。 */
    public String description(McpTool tool) {
        String base = ToolDefinitionSupport.description(tool);
        if (!properties.isMetadataEnrich()) {
            return base;
        }
        Set<String> terms = generatedTerms(tool);
        if (terms.isEmpty()) {
            return base;
        }
        StringBuilder text = new StringBuilder(base == null ? "" : base);
        for (String term : terms) {
            if (!containsIgnoreCase(text, term)) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(term);
            }
        }
        return text.toString().trim();
    }

    /** 增强后的标签（原始 tags + 检索词）。 */
    public List<String> tags(McpTool tool) {
        List<String> tags = new ArrayList<>(ToolDefinitionSupport.tags(tool.getTags()));
        if (!properties.isMetadataEnrich()) {
            return tags;
        }
        Set<String> terms = generatedTerms(tool);
        for (String term : terms) {
            if (!containsIgnoreCase(tags, term)) {
                tags.add(term);
            }
        }
        return tags;
    }

    /**
     * 从 code / name 生成检索词：英文 token + 反向中文同义词。
     * 例如 {@code order_query} → order, query, 订单, 查询。
     */
    private Set<String> generatedTerms(McpTool tool) {
        Set<String> terms = new LinkedHashSet<>();
        if (tool == null) {
            return terms;
        }
        for (String token : tokens(tool.getCode())) {
            terms.add(token);
            for (String chinese : ToolSynonyms.chineseOf(token)) {
                terms.add(chinese);
            }
        }
        for (String token : tokens(tool.getName())) {
            terms.add(token);
        }
        return terms;
    }

    /** 拆分 snake_case / kebab-case / camelCase / 空格分隔；忽略 1 字符与纯数字。 */
    private List<String> tokens(String value) {
        List<String> tokens = new ArrayList<>();
        if (!StringUtils.hasText(value)) {
            return tokens;
        }
        String normalized = value
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase(Locale.ROOT);
        for (String part : normalized.split("[^a-z0-9\\u4e00-\\u9fff]+")) {
            if (part.length() >= 2 && !part.matches("[0-9]+")) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    private boolean containsIgnoreCase(StringBuilder text, String term) {
        return text.toString().toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT));
    }

    private boolean containsIgnoreCase(List<String> values, String term) {
        for (String value : values) {
            if (value != null && value.equalsIgnoreCase(term)) {
                return true;
            }
        }
        return false;
    }
}
