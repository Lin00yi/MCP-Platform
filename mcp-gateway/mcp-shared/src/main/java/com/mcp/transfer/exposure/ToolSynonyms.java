package com.mcp.transfer.exposure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 中英同义词词典（检索扩展与离线元数据增强共用）。
 *
 * <p>词典是双向的：查询“订单”可扩展出 order；工具 code/name 里的 order
 * 也可反向补出“订单”。当前为内置确定性词典，后续可替换为 LLM 离线生成。
 */
public final class ToolSynonyms {

    private static final Map<String, String[]> SYNONYMS = new HashMap<>();
    private static final Map<String, List<String>> REVERSE = new HashMap<>();

    static {
        put("订单", "order", "orders");
        put("查询", "query", "search", "find", "list");
        put("创建", "create", "add", "new");
        put("新增", "create", "add");
        put("删除", "delete", "remove");
        put("更新", "update", "modify", "edit");
        put("退款", "refund");
        put("支付", "pay", "payment");
        put("发货", "ship", "delivery");
        put("物流", "logistics", "express");
        put("用户", "user", "customer");
        put("客户", "customer", "client");
        put("地址", "address");
        put("价格", "price", "amount");
        put("文件", "file");
        put("上传", "upload");
        put("下载", "download");
        put("模板", "template");
        put("报价", "quote", "quotation", "price");
        put("PCB", "pcb", "board");
        put("电路板", "pcb", "board");
        put("库存", "stock", "inventory");
        put("取消", "cancel");
        put("状态", "status", "state");
        put("列表", "list");
        put("详情", "detail", "get");
    }

    private ToolSynonyms() {
    }

    private static void put(String key, String... values) {
        String normalized = key.toLowerCase(Locale.ROOT);
        SYNONYMS.put(normalized, values);
        for (String value : values) {
            String english = value.toLowerCase(Locale.ROOT);
            REVERSE.computeIfAbsent(english, k -> new ArrayList<>()).add(key);
        }
    }

    /** 某个词的同义词（双向）；不存在返回空列表。 */
    public static List<String> of(String term) {
        if (term == null) {
            return Collections.emptyList();
        }
        String key = term.trim().toLowerCase(Locale.ROOT);
        String[] values = SYNONYMS.get(key);
        if (values != null) {
            return Arrays.asList(values);
        }
        List<String> reverse = REVERSE.get(key);
        return reverse == null ? Collections.emptyList() : new ArrayList<>(reverse);
    }

    /** 反向：英文 token → 中文词（用于给英文工具名补中文检索词）。 */
    public static List<String> chineseOf(String english) {
        if (english == null) {
            return Collections.emptyList();
        }
        List<String> values = REVERSE.get(english.trim().toLowerCase(Locale.ROOT));
        return values == null ? Collections.emptyList() : new ArrayList<>(values);
    }

    /** 把词加入集合，并带上其同义词。 */
    public static void expandInto(String term, LinkedHashSet<String> target) {
        if (term == null || term.isEmpty()) {
            return;
        }
        target.add(term);
        for (String synonym : of(term)) {
            if (synonym != null && !synonym.isEmpty()) {
                target.add(synonym);
            }
        }
    }
}
