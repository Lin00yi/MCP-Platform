package com.mcp.transfer.identity;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地静态 token 存储：仅用于本地开发与端到端测试，生产必须由 user-center 提供真实 Store。
 *
 * <p>格式（多条用 {@code ;} 分隔，字段用 {@code |} 分隔）：
 * <pre>
 * token|userId|userName|customerId|scope1,scope2
 * </pre>
 * 示例：
 * <pre>
 * MCP_AUTH_STATIC_TOKENS=e2e-token|u-e2e|E2E 用户|C-E2E|tool:read,tool:write
 * </pre>
 * 不配置时本 Bean 不生效。
 */
@Slf4j
public class StaticUserTokenStore implements UserTokenStore {

    private final Map<String, UserIdentity> tokens = new LinkedHashMap<>();

    public StaticUserTokenStore(String raw) {
        if (!StringUtils.hasText(raw)) {
            return;
        }
        for (String entry : raw.split(";")) {
            if (!StringUtils.hasText(entry)) {
                continue;
            }
            String[] parts = entry.split("\\|", -1);
            if (parts.length < 4 || !StringUtils.hasText(parts[0]) || !StringUtils.hasText(parts[1])) {
                log.warn("[USER_TOKEN] 忽略格式错误的静态 token 配置（需要 token|userId|userName|customerId|scopes）");
                continue;
            }
            UserIdentity identity = new UserIdentity();
            identity.setUserId(parts[1].trim());
            identity.setUserName(parts[2].trim());
            identity.setCustomerId(parts[3].trim());
            identity.setScopes(parseScopes(parts.length > 4 ? parts[4] : ""));
            identity.setTokenType("static");
            identity.setClientLabel("local-static");
            tokens.put(parts[0].trim(), identity);
        }
        log.warn("[USER_TOKEN] 已启用本地静态 token（{} 条），仅限开发/E2E；生产请由 user-center 提供 UserTokenStore", tokens.size());
    }

    @Override
    public UserIdentity find(String rawToken) {
        if (!StringUtils.hasText(rawToken)) {
            return null;
        }
        return tokens.get(rawToken.trim());
    }

    /** 暴露已加载的 token 数，供启动自检使用 */
    public int size() {
        return tokens.size();
    }

    private List<String> parseScopes(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Collections.emptyList();
        }
        List<String> scopes = new ArrayList<>();
        for (String scope : raw.split(",")) {
            if (StringUtils.hasText(scope)) {
                scopes.add(scope.trim());
            }
        }
        return scopes;
    }
}
