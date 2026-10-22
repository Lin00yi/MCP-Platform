package com.mcp.transfer.exposure;

import org.springframework.stereotype.Component;

/**
 * 轻量 token 估算器（不依赖外部 tokenizer）。
 *
 * <p>规则：CJK 字符按 1 token/字；其余字符按 4 字符/token 向上取整。
 * JSON 标点、英文工具名、中文描述混合场景下比单纯 chars/4 更接近真实值，
 * 但仍只是预算估算，不用于计费。
 */
@Component
public class TokenEstimator {

    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long tokens = 0;
        int asciiRun = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isCjk(cp)) {
                if (asciiRun > 0) {
                    tokens += (asciiRun + 3) / 4;
                    asciiRun = 0;
                }
                tokens += 1;
            } else {
                asciiRun++;
            }
        }
        if (asciiRun > 0) {
            tokens += (asciiRun + 3) / 4;
        }
        return (int) Math.max(1, tokens);
    }

    public String method() {
        return "cjk-aware/approx-v1";
    }

    private boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x3040 && cp <= 0x30FF)
                || (cp >= 0xAC00 && cp <= 0xD7AF)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0x3000 && cp <= 0x303F);
    }
}
