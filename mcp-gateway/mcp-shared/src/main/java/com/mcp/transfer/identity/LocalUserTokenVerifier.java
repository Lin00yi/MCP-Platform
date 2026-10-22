package com.mcp.transfer.identity;

import com.mcp.transfer.utils.SecretCrypto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地用户 token 校验器：委托 {@link UserTokenStore}（由 user-center 提供），不发起 HTTP 调用。
 *
 * <p>只缓存 {@code SHA-256(token)} → 身份，TTL 由 {@code mcp.auth.user-token.cache-ttl-seconds}
 * 控制；不缓存原始 token。
 */
@Slf4j
@Component
public class LocalUserTokenVerifier implements UserTokenVerifier {

    private static final int MAX_CACHE_ENTRIES = 10_000;

    private final UserTokenStore userTokenStore;
    private final UserTokenProperties properties;
    private final SecretCrypto secretCrypto;

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Autowired
    public LocalUserTokenVerifier(UserTokenStore userTokenStore,
                                  UserTokenProperties properties,
                                  SecretCrypto secretCrypto) {
        this.userTokenStore = userTokenStore;
        this.properties = properties;
        this.secretCrypto = secretCrypto;
    }

    @Override
    public UserIdentity verify(String rawToken) {
        if (!StringUtils.hasText(rawToken)) {
            return null;
        }
        String cacheKey = secretCrypto.sha256Hex(rawToken);
        if (cacheKey != null) {
            CacheEntry cached = cache.get(cacheKey);
            if (cached != null && cached.expireAt > System.currentTimeMillis()) {
                return cached.identity;
            }
            cache.remove(cacheKey);
        }

        UserIdentity identity = userTokenStore.find(rawToken);
        cache(cacheKey, identity);
        return identity;
    }

    private void cache(String cacheKey, UserIdentity identity) {
        if (cacheKey == null || identity == null) {
            return;
        }
        long ttlMs = Math.max(0, properties.getCacheTtlSeconds()) * 1000L;
        if (identity.getExpiresAt() != null && identity.getExpiresAt() > 0) {
            long expMs = identity.getExpiresAt() * 1000L;
            ttlMs = Math.min(ttlMs, Math.max(0, expMs - System.currentTimeMillis()));
        }
        if (ttlMs <= 0) {
            return;
        }
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.clear();
        }
        CacheEntry entry = new CacheEntry();
        entry.identity = identity;
        entry.expireAt = System.currentTimeMillis() + ttlMs;
        cache.put(cacheKey, entry);
    }

    private static class CacheEntry {
        private UserIdentity identity;
        private long expireAt;
    }
}
