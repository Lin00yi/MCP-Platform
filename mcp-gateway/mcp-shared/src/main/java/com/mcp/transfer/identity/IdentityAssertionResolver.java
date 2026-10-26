package com.mcp.transfer.identity;

import com.mcp.transfer.shared.entity.McpServer;
import com.mcp.transfer.upstream.UpstreamDef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内部身份断言的统一解析：把「Server / 聚合上游配置 + 当前请求身份」转换成可下传的 JWT/JWE。
 *
 * <p>这是 OPTIONAL / REQUIRED 两种模式的唯一判定点：
 * <ul>
 *   <li>OPTIONAL：缺用户、缺客编、缺下游公钥、签名失败时记录一次告警后返回 {@code null}；</li>
 *   <li>REQUIRED：上述任一情况直接抛 {@link IdentityRequiredException}，由数据面转成 -32003；</li>
 *   <li>OFF：始终返回 {@code null}。</li>
 * </ul>
 *
 * <p>探活使用独立的 service 断言（{@code sub=gateway-health-probe, scope=[health]}），不要求客编；
 * 下游可用 {@link com.mcp.transfer.identity.verifier.GatewayOriginVerifier} 验签并按 scope 放行。
 */
@Slf4j
@Component
public class IdentityAssertionResolver {

    /** 探活断言的固定 subject，下游据此识别 */
    public static final String PROBE_SUBJECT = "gateway-health-probe";
    /** 探活断言的 scope */
    public static final String HEALTH_SCOPE = "health";

    private static final int MAX_WARN_KEYS = 512;

    private final IdentityProperties properties;
    private final InternalIdentityTokenService tokenService;

    /** 同一 Server 同一原因只告警一次，避免 OPTIONAL 灰度期间刷日志 */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final AtomicInteger warnCount = new AtomicInteger();

    public IdentityAssertionResolver(IdentityProperties properties,
                                     InternalIdentityTokenService tokenService) {
        this.properties = properties;
        this.tokenService = tokenService;
    }

    /**
     * 为普通请求签发断言。
     *
     * @param server        当前 Server（单代理或聚合 Server）
     * @param modeOverride  聚合上游的模式覆盖，可为空
     * @param audience      聚合上游的 audience 覆盖，可为空
     * @param publicKey     聚合上游的公钥覆盖，可为空
     * @return 断言；OFF 或 OPTIONAL 降级时为 null
     * @throws IdentityRequiredException REQUIRED 模式下无法签发
     */
    public String resolve(McpServer server, String modeOverride, String audience, String publicKey) {
        if (server == null) {
            return null;
        }
        IdentityMode mode = IdentityModes.effective(properties,
                StringUtils.hasText(modeOverride) ? modeOverride : server.getIdentityMode());
        if (!mode.enabled()) {
            return null;
        }

        ResolvedIdentity identity = McpIdentityContext.get();
        String missing = missingReason(identity);
        if (missing != null) {
            return degrade(mode, server, missing);
        }

        String effectiveAudience = firstText(audience, server.getAudience(),
                "server-" + server.getId());
        String effectiveKey = firstText(publicKey, server.getIdentityPublicKey());
        try {
            return tokenService.issue(identity, effectiveAudience, effectiveKey);
        } catch (Exception e) {
            return degrade(mode, server, "签发失败: " + e.getMessage());
        }
    }

    /** 单代理场景的便捷入口 */
    public String resolve(McpServer server) {
        return resolve(server, null, null, null);
    }

    /**
     * 为健康探活签发 service 断言；不要求当前请求身份与客编。
     *
     * @throws IdentityRequiredException REQUIRED 模式下无法签发
     */
    public String resolveForHealth(McpServer server, UpstreamDef upstream) {
        if (server == null) {
            return null;
        }
        String modeValue = upstream != null && StringUtils.hasText(upstream.getIdentityMode())
                ? upstream.getIdentityMode() : server.getIdentityMode();
        IdentityMode mode = IdentityModes.effective(properties, modeValue);
        if (!mode.enabled()) {
            return null;
        }

        String audience = upstream != null && StringUtils.hasText(upstream.getAudience())
                ? upstream.getAudience() : firstText(server.getAudience(), "server-" + server.getId());
        String publicKey = upstream != null && StringUtils.hasText(upstream.getIdentityPublicKey())
                ? upstream.getIdentityPublicKey() : server.getIdentityPublicKey();

        ResolvedIdentity probe = new ResolvedIdentity();
        probe.setUserId(PROBE_SUBJECT);
        probe.setScopes(Collections.singletonList(HEALTH_SCOPE));
        try {
            return tokenService.issue(probe, audience, publicKey);
        } catch (Exception e) {
            return degrade(mode, server, "探活断言签发失败: " + e.getMessage());
        }
    }

    private String missingReason(ResolvedIdentity identity) {
        if (identity == null || !StringUtils.hasText(identity.getUserId())) {
            return "缺少用户身份";
        }
        if (!StringUtils.hasText(identity.getCustomerId())) {
            return "未取得当前客编";
        }
        return null;
    }

    private String degrade(IdentityMode mode, McpServer server, String reason) {
        if (mode == IdentityMode.REQUIRED) {
            throw new IdentityRequiredException(
                    "身份下传失败（server=" + server.getName() + "）：" + reason);
        }
        warnOnce(server, reason);
        return null;
    }

    private void warnOnce(McpServer server, String reason) {
        String key = server.getId() + "|" + reason;
        if (warnCount.get() > MAX_WARN_KEYS) {
            warned.clear();
            warnCount.set(0);
        }
        if (warned.add(key)) {
            warnCount.incrementAndGet();
            log.warn("[IDENTITY] server={} 未下发内部断言（OPTIONAL 降级）：{}",
                    server.getName(), reason);
        }
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }
}
