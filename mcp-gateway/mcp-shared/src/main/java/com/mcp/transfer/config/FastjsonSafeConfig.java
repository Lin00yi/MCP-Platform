package com.mcp.transfer.config;

import com.alibaba.fastjson.parser.ParserConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * fastjson 1.x 安全兜底：开启 safeMode，禁止 autoType 反序列化。
 *
 * <p>项目内部只用 fastjson 做 JSON 编解码（不反序列化任意类型），仍启用 safeMode 作为纵深防御，
 * 避免历史 CVE 类反序列化风险；后续新代码建议统一使用 Jackson。
 */
@Component
public class FastjsonSafeConfig {

    private static final Logger log = LoggerFactory.getLogger(FastjsonSafeConfig.class);

    @PostConstruct
    public void init() {
        try {
            ParserConfig.getGlobalInstance().setSafeMode(true);
            log.info("[SECURITY] fastjson safeMode 已开启（禁止 autoType）");
        } catch (Throwable e) {
            log.warn("[SECURITY] fastjson safeMode 开启失败：{}", e.getMessage());
        }
    }
}
