package com.mcp.transfer.exposure;

import com.alibaba.fastjson.JSONObject;
import com.mcp.transfer.entity.McpTool;
import com.mcp.transfer.mapper.ToolCallRecordMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具检索（META 模式的 search_tools）。
 *
 * <p>支持四级 + 目录模式，由 {@code mcp.tools.search-mode} 配置：
 * <ul>
 *   <li>{@link Mode#CATALOG}：不打分，按业务分组列目录（query 为空时默认）；</li>
 *   <li>{@link Mode#KEYWORD}：字段加权的关键词匹配（轻量、零依赖）；</li>
 *   <li>{@link Mode#BM25}：标准 BM25 全文检索（无 embedding 时的默认）；</li>
 *   <li>{@link Mode#VECTOR}：向量语义检索；</li>
 *   <li>{@link Mode#HYBRID}：BM25 + 向量，RRF（k=60）融合（有 embedding 时的默认）。</li>
 * </ul>
 *
 * <p>检索对象是 {@link ToolDoc}，同时覆盖单 Server 与 Endpoint 跨 namespace 场景；
 * 结果只返回轻量摘要，Schema 由 get_tool_schema 按需获取。
 */
@Component
public class ToolSearchService {

    /** BM25 参数 */
    private static final double BM25_K1 = 1.2;
    private static final double BM25_B = 0.75;
    /** RRF 常数 */
    private static final int RRF_K = 60;
    /** 向量召回最低相似度 */
    private static final double VECTOR_MIN_SCORE = 0.25;

    @Autowired
    private ToolExposureProperties properties;

    @Autowired(required = false)
    private ToolCallRecordMapper toolCallRecordMapper;

    @Autowired(required = false)
    private ToolEmbeddingService embeddingService;

    @Autowired(required = false)
    private ToolMetadataEnricher metadataEnricher;

    private final Map<Integer, PopularitySnapshot> popularityCache = new ConcurrentHashMap<>();

    // ==================== 新模式（推荐） ====================

    /** 检索模式 */
    public enum Mode {
        AUTO, CATALOG, KEYWORD, BM25, VECTOR, HYBRID
    }

    /** 一次检索请求；由元工具参数解析而来。 */
    public static final class SearchRequest {
        private String query;
        private List<String> tags = new ArrayList<>();
        private String domain;
        private String server;
        private int topK = 10;
        private Mode mode;

        public String getQuery() {
            return query;
        }

        public void setQuery(String query) {
            this.query = query;
        }

        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags == null ? new ArrayList<>() : tags;
        }

        public String getDomain() {
            return domain;
        }

        public void setDomain(String domain) {
            this.domain = domain;
        }

        public String getServer() {
            return server;
        }

        public void setServer(String server) {
            this.server = server;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public Mode getMode() {
            return mode;
        }

        public void setMode(Mode mode) {
            this.mode = mode;
        }

        public boolean hasQuery() {
            return StringUtils.hasText(query);
        }
    }

    /** 参与检索的工具文档（native / 代理统一视图）。 */
    public static final class ToolDoc {
        private String code;
        private String exposedName;
        private String domain;
        private String name;
        private String description;
        private List<String> tags = new ArrayList<>();
        private Integer serverId;
        private Integer toolId;
        private McpTool source;

        public static ToolDoc of(McpTool tool, String exposedName, String domain) {
            return of(tool, exposedName, domain, null);
        }

        public static ToolDoc of(McpTool tool, String exposedName, String domain, Integer serverIdFallback) {
            ToolDoc doc = new ToolDoc();
            doc.source = tool;
            doc.code = tool.getCode();
            doc.exposedName = StringUtils.hasText(exposedName) ? exposedName : tool.getCode();
            doc.domain = domain;
            doc.name = tool.getName();
            doc.description = ToolDefinitionSupport.description(tool);
            doc.tags = ToolDefinitionSupport.tags(tool.getTags());
            doc.serverId = tool.getServerId() != null ? tool.getServerId() : serverIdFallback;
            doc.toolId = tool.getId();
            return doc;
        }

        public String getCode() {
            return code;
        }

        public String getExposedName() {
            return exposedName;
        }

        public String getDomain() {
            return domain;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public List<String> getTags() {
            return tags;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public void setTags(List<String> tags) {
            this.tags = tags == null ? new ArrayList<>() : tags;
        }

        public Integer getServerId() {
            return serverId;
        }

        public Integer getToolId() {
            return toolId;
        }

        public McpTool getSource() {
            return source;
        }
    }

    public static final class ScoredDoc {
        private final ToolDoc doc;
        private final double score;

        public ScoredDoc(ToolDoc doc, double score) {
            this.doc = doc;
            this.score = score;
        }

        public ToolDoc getDoc() {
            return doc;
        }

        public double getScore() {
            return score;
        }
    }

    public static final class SearchResult {
        private final List<ScoredDoc> hits;
        private final Mode mode;

        public SearchResult(List<ScoredDoc> hits, Mode mode) {
            this.hits = hits;
            this.mode = mode;
        }

        public List<ScoredDoc> getHits() {
            return hits;
        }

        public Mode getMode() {
            return mode;
        }
    }

    /**
     * 解析元工具参数，兼容旧字段（tag / limit）。
     */
    public static SearchRequest parseRequest(JSONObject args, ToolExposureProperties properties) {
        SearchRequest request = new SearchRequest();
        if (args == null) {
            return request;
        }
        request.setQuery(args.getString("query"));
        request.setDomain(trimToNull(args.getString("domain")));
        request.setServer(firstText(args, "server", "server_id", "serverId"));

        Object tags = args.get("tags");
        if (tags instanceof Iterable) {
            for (Object value : (Iterable<?>) tags) {
                if (value != null && StringUtils.hasText(String.valueOf(value))) {
                    request.getTags().add(String.valueOf(value).trim());
                }
            }
        } else if (tags != null && StringUtils.hasText(String.valueOf(tags))) {
            for (String tag : String.valueOf(tags).split(",")) {
                if (StringUtils.hasText(tag)) {
                    request.getTags().add(tag.trim());
                }
            }
        }
        String legacyTag = args.getString("tag");
        if (StringUtils.hasText(legacyTag)) {
            request.getTags().add(legacyTag.trim());
        }

        Integer topK = args.getInteger("top_k");
        if (topK == null) {
            topK = args.getInteger("limit");
        }
        int max = Math.max(1, properties.getMaxSearchLimit());
        int size = topK == null || topK <= 0 ? properties.getSearchLimit() : Math.min(topK, max);
        request.setTopK(Math.max(1, Math.min(size, max)));
        return request;
    }

    private static String firstText(JSONObject args, String... keys) {
        for (String key : keys) {
            String value = args.getString(key);
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /** 统一检索入口：按配置/请求解析策略后打分排序。 */
    public SearchResult searchDocuments(List<ToolDoc> documents, SearchRequest request) {
        SearchRequest req = request == null ? new SearchRequest() : request;
        List<ToolDoc> filtered = filterDocuments(documents, req);
        Mode mode = effectiveMode(req);
        if (filtered.isEmpty()) {
            return new SearchResult(Collections.emptyList(), mode);
        }
        if (mode == Mode.CATALOG || !req.hasQuery()) {
            List<ToolDoc> sorted = new ArrayList<>(filtered);
            sorted.sort(Comparator
                    .comparing(ToolDoc::getDomain, Comparator.nullsLast(String::compareTo))
                    .thenComparing(ToolDoc::getExposedName, Comparator.nullsLast(String::compareTo)));
            List<ScoredDoc> hits = new ArrayList<>();
            for (int i = 0; i < sorted.size() && i < req.getTopK(); i++) {
                hits.add(new ScoredDoc(sorted.get(i), 0));
            }
            return new SearchResult(hits, Mode.CATALOG);
        }

        List<ScoredDoc> hits;
        switch (mode) {
            case KEYWORD:
                hits = keyword(filtered, req);
                break;
            case VECTOR:
                hits = vector(filtered, req);
                if (hits.isEmpty()) {
                    hits = bm25(filtered, req);
                    mode = Mode.BM25;
                }
                break;
            case HYBRID:
                List<ScoredDoc> bm = bm25(filtered, req);
                List<ScoredDoc> vec = vector(filtered, req);
                if (vec.isEmpty()) {
                    hits = bm;
                    mode = Mode.BM25;
                } else {
                    hits = rrf(bm, vec);
                }
                break;
            case BM25:
            default:
                hits = bm25(filtered, req);
                mode = Mode.BM25;
                break;
        }
        return new SearchResult(limit(hits, req.getTopK()), mode);
    }

    private Mode effectiveMode(SearchRequest request) {
        Mode configured = request.getMode() != null ? request.getMode() : modeOf(properties.getSearchMode());
        if (configured != Mode.AUTO) {
            return configured;
        }
        if (!request.hasQuery()) {
            return Mode.CATALOG;
        }
        return embeddingAvailable() ? Mode.HYBRID : Mode.BM25;
    }

    public static Mode modeOf(String value) {
        if (!StringUtils.hasText(value)) {
            return Mode.AUTO;
        }
        try {
            return Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return Mode.AUTO;
        }
    }

    private boolean embeddingAvailable() {
        return embeddingService != null && embeddingService.enabled();
    }

    /** 应用 domain/server/tags 过滤；目录模式也复用同一套规则。 */
    public List<ToolDoc> filterDocuments(List<ToolDoc> documents, SearchRequest request) {
        List<ToolDoc> result = new ArrayList<>();
        if (documents == null) {
            return result;
        }
        String domain = request.getDomain();
        String server = request.getServer();
        List<String> tags = request.getTags();
        for (ToolDoc doc : documents) {
            if (doc == null || !StringUtils.hasText(doc.getCode())) {
                continue;
            }
            enrich(doc);
            if (MetaToolFactory.isMetaTool(doc.getCode())
                    || MetaToolFactory.isMetaTool(doc.getExposedName())) {
                continue;
            }
            if (StringUtils.hasText(domain) && !domain.equalsIgnoreCase(doc.getDomain())) {
                continue;
            }
            if (StringUtils.hasText(server) && !matchesServer(doc, server)) {
                continue;
            }
            if (!tags.isEmpty() && !matchesTags(doc, tags)) {
                continue;
            }
            result.add(doc);
        }
        return result;
    }

    /** 离线元数据增强：补描述与标签，提升英文 code 的中文召回。 */
    private void enrich(ToolDoc doc) {
        if (metadataEnricher == null || doc.getSource() == null) {
            return;
        }
        doc.setDescription(metadataEnricher.description(doc.getSource()));
        doc.setTags(metadataEnricher.tags(doc.getSource()));
    }

    private boolean matchesServer(ToolDoc doc, String server) {
        if (doc.getDomain() != null && server.equalsIgnoreCase(doc.getDomain())) {
            return true;
        }
        return doc.getServerId() != null && server.equals(String.valueOf(doc.getServerId()));
    }

    private boolean matchesTags(ToolDoc doc, List<String> tags) {
        for (String tag : tags) {
            for (String docTag : doc.getTags()) {
                if (tag.equalsIgnoreCase(docTag)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ==================== 各模式打分 ====================

    private List<ScoredDoc> keyword(List<ToolDoc> docs, SearchRequest request) {
        List<String> terms = terms(request.getQuery());
        List<ScoredDoc> hits = new ArrayList<>();
        for (ToolDoc doc : docs) {
            int score = keywordScore(doc, request.getQuery(), terms);
            if (score > 0) {
                hits.add(new ScoredDoc(doc, score));
            }
        }
        return sort(hits);
    }

    private int keywordScore(ToolDoc doc, String rawQuery, List<String> terms) {
        String query = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);
        String code = lower(doc.getCode());
        String name = lower(doc.getName());
        String tags = lower(String.join(",", doc.getTags()));
        String description = lower(doc.getDescription());

        int score = 0;
        if (!query.isEmpty()) {
            if (query.equals(code)) {
                score += 120;
            } else if (!code.isEmpty() && code.startsWith(query)) {
                score += 70;
            } else if (!code.isEmpty() && code.contains(query)) {
                score += 50;
            }
            if (!name.isEmpty() && name.contains(query)) {
                score += 35;
            }
            if (!tags.isEmpty() && tags.contains(query)) {
                score += 30;
            }
            if (!description.isEmpty() && description.contains(query)) {
                score += 15;
            }
        }
        for (String term : terms) {
            if (!code.isEmpty() && code.contains(term)) {
                score += 10;
            } else if (!name.isEmpty() && name.contains(term)) {
                score += 7;
            } else if (!tags.isEmpty() && tags.contains(term)) {
                score += 6;
            } else if (!description.isEmpty() && description.contains(term)) {
                score += 3;
            }
        }
        return score;
    }

    /** 标准 BM25；文档 token 为 ASCII 词 + CJK 单字/二元组。 */
    private List<ScoredDoc> bm25(List<ToolDoc> docs, SearchRequest request) {
        List<String> queryTerms = terms(request.getQuery());
        if (queryTerms.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Integer>> tfs = new ArrayList<>(docs.size());
        int[] lengths = new int[docs.size()];
        Map<String, Integer> df = new HashMap<>();
        double totalLength = 0;
        for (int i = 0; i < docs.size(); i++) {
            Map<String, Integer> tf = docTokens(docs.get(i));
            tfs.add(tf);
            int length = 0;
            for (Integer count : tf.values()) {
                length += count;
            }
            lengths[i] = Math.max(1, length);
            totalLength += lengths[i];
            for (String term : tf.keySet()) {
                df.merge(term, 1, Integer::sum);
            }
        }
        double avgLength = totalLength / Math.max(1, docs.size());
        List<ScoredDoc> hits = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            Map<String, Integer> tf = tfs.get(i);
            double score = 0;
            for (String term : queryTerms) {
                Integer count = tf.get(term);
                if (count == null || count <= 0) {
                    continue;
                }
                int docFreq = df.getOrDefault(term, 0);
                double idf = Math.log(1 + (docs.size() - docFreq + 0.5) / (docFreq + 0.5));
                double denominator = count + BM25_K1 * (1 - BM25_B + BM25_B * lengths[i] / avgLength);
                score += idf * count * (BM25_K1 + 1) / denominator;
            }
            score += codeBoost(request.getQuery(), docs.get(i).getCode());
            if (score <= 0) {
                continue;
            }
            Integer calls = popularity(docs.get(i).getServerId()).get(docs.get(i).getCode());
            if (calls != null && calls > 0) {
                score += Math.min(1.0, calls / 50.0);
            }
            hits.add(new ScoredDoc(docs.get(i), score));
        }
        return sort(hits);
    }

    private List<ScoredDoc> vector(List<ToolDoc> docs, SearchRequest request) {
        if (!embeddingAvailable() || !request.hasQuery()) {
            return Collections.emptyList();
        }
        float[] queryVector = embeddingService.queryVector(request.getQuery());
        if (queryVector == null) {
            return Collections.emptyList();
        }
        List<ScoredDoc> hits = new ArrayList<>();
        for (ToolDoc doc : docs) {
            if (doc.getServerId() == null || doc.getToolId() == null) {
                continue;
            }
            float[] vector = embeddingService.vector(doc.getServerId(), doc.getToolId());
            if (vector == null) {
                continue;
            }
            double score = ToolEmbeddingService.cosine(queryVector, vector);
            if (score >= VECTOR_MIN_SCORE) {
                hits.add(new ScoredDoc(doc, score));
            }
        }
        return sort(hits);
    }

    /** 倒数排序融合（RRF）：只依赖名次，不需要配平两路分数尺度。 */
    private List<ScoredDoc> rrf(List<ScoredDoc> bm25, List<ScoredDoc> vector) {
        Map<ToolDoc, Double> fused = new LinkedHashMap<>();
        Map<ToolDoc, ToolDoc> docs = new LinkedHashMap<>();
        for (int i = 0; i < bm25.size(); i++) {
            ToolDoc doc = bm25.get(i).getDoc();
            docs.put(doc, doc);
            fused.merge(doc, 1.0 / (RRF_K + i + 1), Double::sum);
        }
        for (int i = 0; i < vector.size(); i++) {
            ToolDoc doc = vector.get(i).getDoc();
            docs.put(doc, doc);
            fused.merge(doc, 1.0 / (RRF_K + i + 1), Double::sum);
        }
        List<ScoredDoc> hits = new ArrayList<>();
        for (Map.Entry<ToolDoc, Double> entry : fused.entrySet()) {
            hits.add(new ScoredDoc(entry.getKey(), entry.getValue()));
        }
        return sort(hits);
    }

    private List<ScoredDoc> sort(List<ScoredDoc> hits) {
        hits.sort(Comparator.comparingDouble(ScoredDoc::getScore).reversed()
                .thenComparing(hit -> hit.getDoc().getExposedName(),
                        Comparator.nullsLast(String::compareTo)));
        return hits;
    }

    private List<ScoredDoc> limit(List<ScoredDoc> hits, int topK) {
        if (hits.size() <= topK) {
            return hits;
        }
        return new ArrayList<>(hits.subList(0, topK));
    }

    private double codeBoost(String query, String code) {
        if (!StringUtils.hasText(query) || !StringUtils.hasText(code)) {
            return 0;
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        String c = code.toLowerCase(Locale.ROOT);
        if (q.equals(c)) {
            return 5.0;
        }
        if (c.startsWith(q)) {
            return 1.5;
        }
        if (c.contains(q)) {
            return 0.8;
        }
        return 0;
    }

    /** 文档分词：ASCII 词 + CJK 单字 + CJK 二元组（不做同义词扩展，避免虚增词频）。 */
    private Map<String, Integer> docTokens(ToolDoc doc) {
        String text = lower(doc.getCode()) + " " + lower(doc.getName()) + " "
                + lower(String.join(" ", doc.getTags())) + " " + lower(doc.getDescription());
        Map<String, Integer> tokens = new HashMap<>();
        StringBuilder ascii = new StringBuilder();
        List<Integer> cjk = new ArrayList<>();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isCjk(cp)) {
                flushAscii(ascii, tokens);
                cjk.add(cp);
                addToken(tokens, new String(Character.toChars(cp)));
            } else if (Character.isLetterOrDigit(cp)) {
                ascii.appendCodePoint(cp);
            } else {
                flushAscii(ascii, tokens);
                flushCjk(cjk, tokens);
            }
        }
        flushAscii(ascii, tokens);
        flushCjk(cjk, tokens);
        return tokens;
    }

    private void flushAscii(StringBuilder ascii, Map<String, Integer> tokens) {
        if (ascii.length() > 0) {
            addToken(tokens, ascii.toString());
            ascii.setLength(0);
        }
    }

    private void flushCjk(List<Integer> cjk, Map<String, Integer> tokens) {
        for (int i = 0; i + 1 < cjk.size(); i++) {
            addToken(tokens, new String(Character.toChars(cjk.get(i)))
                    + new String(Character.toChars(cjk.get(i + 1))));
        }
        cjk.clear();
    }

    private void addToken(Map<String, Integer> tokens, String token) {
        if (StringUtils.hasText(token)) {
            tokens.merge(token, 1, Integer::sum);
        }
    }

    // ==================== 兼容旧 API ====================

    public List<ScoredTool> search(List<McpTool> tools, String query, String tag, Integer limit) {
        return search(null, tools, query, tag, limit);
    }

    public List<ScoredTool> search(Integer serverId, List<McpTool> tools, String query, String tag, Integer limit) {
        SearchRequest request = new SearchRequest();
        request.setQuery(query);
        if (StringUtils.hasText(tag)) {
            request.getTags().add(tag.trim());
        }
        int max = Math.max(1, properties.getMaxSearchLimit());
        int size = limit == null || limit <= 0 ? properties.getSearchLimit() : Math.min(limit, max);
        request.setTopK(Math.max(1, Math.min(size, max)));

        List<ToolDoc> docs = new ArrayList<>();
        if (tools != null) {
            for (McpTool tool : tools) {
                if (tool == null) {
                    continue;
                }
                Integer sid = tool.getServerId() != null ? tool.getServerId() : serverId;
                docs.add(ToolDoc.of(tool, tool.getCode(),
                        sid == null ? null : String.valueOf(sid), sid));
            }
        }
        SearchResult result = searchDocuments(docs, request);
        List<ScoredTool> hits = new ArrayList<>();
        for (ScoredDoc scored : result.getHits()) {
            if (scored.getDoc().getSource() != null) {
                hits.add(new ScoredTool(scored.getDoc().getSource(),
                        (int) Math.round(scored.getScore())));
            }
        }
        return hits;
    }

    /** 按 code 精确查找（META 模式 get_tool_schema / call_tool 复用） */
    public McpTool findByCode(List<McpTool> tools, String code) {
        if (tools == null || !StringUtils.hasText(code)) {
            return null;
        }
        for (McpTool tool : tools) {
            if (code.equals(tool.getCode())) {
                return tool;
            }
        }
        return null;
    }

    // ==================== 查询分词 / 同义词 ====================

    /**
     * 查询分词：英文/数字按词，中文按单字 + 二元组，再做同义词扩展。
     * 例如“订单查询” -> 订单、查询、订单查询、order、query...
     */
    private List<String> terms(String query) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        if (!StringUtils.hasText(query)) {
            return new ArrayList<>(terms);
        }
        for (String segment : query.trim().toLowerCase(Locale.ROOT).split("[\\s,，|/]+")) {
            expand(segment, terms);
        }
        if (terms.size() > 30) {
            return new ArrayList<>(terms).subList(0, 30);
        }
        return new ArrayList<>(terms);
    }

    private void expand(String segment, LinkedHashSet<String> terms) {
        if (!StringUtils.hasText(segment)) {
            return;
        }
        terms.add(segment);
        addSynonyms(segment, terms);

        StringBuilder ascii = new StringBuilder();
        List<Integer> cjk = new ArrayList<>();
        for (int i = 0; i < segment.length(); ) {
            int cp = segment.codePointAt(i);
            i += Character.charCount(cp);
            if (isCjk(cp)) {
                if (ascii.length() > 0) {
                    terms.add(ascii.toString());
                    addSynonyms(ascii.toString(), terms);
                    ascii.setLength(0);
                }
                cjk.add(cp);
                terms.add(new String(Character.toChars(cp)));
            } else if (Character.isLetterOrDigit(cp)) {
                ascii.appendCodePoint(cp);
            } else {
                if (ascii.length() > 0) {
                    terms.add(ascii.toString());
                    addSynonyms(ascii.toString(), terms);
                    ascii.setLength(0);
                }
                flushCjkTerms(cjk, terms);
            }
        }
        if (ascii.length() > 0) {
            terms.add(ascii.toString());
            addSynonyms(ascii.toString(), terms);
        }
        flushCjkTerms(cjk, terms);
    }

    private void flushCjkTerms(List<Integer> cjk, LinkedHashSet<String> terms) {
        for (int i = 0; i + 1 < cjk.size(); i++) {
            terms.add(new String(Character.toChars(cjk.get(i)))
                    + new String(Character.toChars(cjk.get(i + 1))));
        }
        cjk.clear();
    }

    private void addSynonyms(String term, LinkedHashSet<String> terms) {
        for (String synonym : ToolSynonyms.of(term)) {
            if (synonym != null && !synonym.isEmpty()) {
                terms.add(synonym);
            }
        }
    }

    // ==================== 热度 ====================

    private Map<String, Integer> popularity(Integer serverId) {
        if (serverId == null || toolCallRecordMapper == null) {
            return Collections.emptyMap();
        }
        long now = System.currentTimeMillis();
        PopularitySnapshot snapshot = popularityCache.get(serverId);
        if (snapshot != null && now - snapshot.at < 60_000L && snapshot.data != null) {
            return snapshot.data;
        }
        Map<String, Integer> data = new HashMap<>();
        try {
            Date since = new Date(now - 30L * 86400000L);
            List<Map<String, Object>> rows = toolCallRecordMapper.topToolCodes(serverId, since, 200);
            if (rows != null) {
                for (Map<String, Object> row : rows) {
                    Object code = row.get("methodCode");
                    Object calls = row.get("calls");
                    if (code != null && calls instanceof Number) {
                        data.put(String.valueOf(code), ((Number) calls).intValue());
                    }
                }
            }
        } catch (Exception ignore) {
            // 热度只是排序微调，失败不影响检索
        }
        popularityCache.put(serverId, new PopularitySnapshot(now, data));
        return data;
    }

    public void setEmbeddingService(ToolEmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    public ToolEmbeddingService getEmbeddingService() {
        return embeddingService;
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x3040 && cp <= 0x30FF)
                || (cp >= 0xAC00 && cp <= 0xD7AF)
                || (cp >= 0xF900 && cp <= 0xFAFF);
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static class PopularitySnapshot {
        private final long at;
        private final Map<String, Integer> data;

        private PopularitySnapshot(long at, Map<String, Integer> data) {
            this.at = at;
            this.data = data;
        }
    }

    /** 检索引擎命中项：带分数，便于管理台/日志观察排序是否合理 */
    public static class ScoredTool {
        private final McpTool tool;
        private final int score;

        public ScoredTool(McpTool tool, int score) {
            this.tool = tool;
            this.score = score;
        }

        public McpTool getTool() {
            return tool;
        }

        public int getScore() {
            return score;
        }
    }
}
