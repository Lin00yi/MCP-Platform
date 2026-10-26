package com.mcp.transfer.shared.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretCryptoTest {

    private SecretCrypto crypto;

    @BeforeEach
    void setUp() {
        crypto = new SecretCrypto();
        crypto.init();
    }

    @Test
    void encryptDecryptRoundTrip() {
        String plain = "{\"token\":\"abc123\"}";
        String encrypted = crypto.encrypt(plain);
        assertTrue(encrypted.startsWith("enc:v1:"));
        assertEquals(plain, crypto.decrypt(encrypted));
    }

    @Test
    void encryptIsIdempotentForAlreadyEncryptedValue() {
        String encrypted = crypto.encrypt("secret");
        assertEquals(encrypted, crypto.encrypt(encrypted));
    }

    @Test
    void maskBearerHidesToken() {
        assertEquals("Bearer abc***xyz", crypto.maskBearer("Bearer abcdefghijklmnxyz"));
        assertEquals("Bearer ***", crypto.maskBearer("Bearer short"));
        assertEquals("***", crypto.maskBearer("Basic abc"));
    }

    @Test
    void maskJsonSecretsMasksSensitiveFields() {
        String json = "{\"token\":\"abc\",\"app_secret\":\"def\",\"name\":\"alice\"}";
        String masked = crypto.maskJsonSecrets(json);
        assertNotNull(masked);
        assertTrue(masked.contains("\"token\":\"***\""));
        assertTrue(masked.contains("app_secret\":\"***\""));
        assertTrue(masked.contains("\"name\":\"alice\""));
    }
}
