package com.mcp.transfer.identity;

import com.mcp.transfer.utils.SecretCrypto;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalUserTokenVerifierTest {

    @Test
    void validTokenReturnsIdentity() {
        LocalUserTokenVerifier verifier = verifier(token -> {
            UserIdentity identity = new UserIdentity();
            identity.setUserId("u-1");
            identity.setCustomerId("C-1");
            return identity;
        }, 30);

        UserIdentity identity = verifier.verify("dsh_abc");

        assertEquals("u-1", identity.getUserId());
        assertEquals("C-1", identity.getCustomerId());
    }

    @Test
    void invalidTokenReturnsNull() {
        LocalUserTokenVerifier verifier = verifier(token -> null, 30);

        assertNull(verifier.verify("dsh_bad"));
    }

    @Test
    void missingTokenReturnsNull() {
        AtomicInteger calls = new AtomicInteger();
        LocalUserTokenVerifier verifier = verifier(token -> {
            calls.incrementAndGet();
            return null;
        }, 30);

        assertNull(verifier.verify(null));
        assertNull(verifier.verify("   "));
        assertEquals(0, calls.get());
    }

    @Test
    void unavailableStoreIsPropagated() {
        LocalUserTokenVerifier verifier = verifier(token -> {
            throw new UserTokenUnavailableException("store down");
        }, 30);

        assertThrows(UserTokenUnavailableException.class, () -> verifier.verify("dsh_abc"));
    }

    @Test
    void secondCallUsesCache() {
        AtomicInteger calls = new AtomicInteger();
        LocalUserTokenVerifier verifier = verifier(token -> {
            calls.incrementAndGet();
            UserIdentity identity = new UserIdentity();
            identity.setUserId("u-1");
            return identity;
        }, 30);

        verifier.verify("dsh_abc");
        verifier.verify("dsh_abc");

        assertEquals(1, calls.get());
    }

    private LocalUserTokenVerifier verifier(UserTokenStore store, int cacheTtlSeconds) {
        UserTokenProperties properties = new UserTokenProperties();
        properties.setCacheTtlSeconds(cacheTtlSeconds);
        return new LocalUserTokenVerifier(store, properties, new SecretCrypto());
    }
}
