package com.cpq.datasource;

import com.cpq.datasource.service.EncryptionService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class EncryptionServiceTest {

    @Inject
    EncryptionService encryptionService;

    @Test
    void encryptDecryptRoundTrip() {
        String plain = "Bearer my-secret-token-12345";
        String encrypted = encryptionService.encrypt(plain);
        assertNotEquals(plain, encrypted);
        assertTrue(encrypted.startsWith("ENC:"));
        assertEquals(plain, encryptionService.decrypt(encrypted));
    }

    @Test
    void encryptProducesDifferentCiphertextsEachTime() {
        String plain = "Bearer token";
        String enc1 = encryptionService.encrypt(plain);
        String enc2 = encryptionService.encrypt(plain);
        // IV is random so ciphertexts differ
        assertNotEquals(enc1, enc2);
        // But both decrypt to the same plaintext
        assertEquals(plain, encryptionService.decrypt(enc1));
        assertEquals(plain, encryptionService.decrypt(enc2));
    }

    @Test
    void maskReturnsStars() {
        assertEquals("****", encryptionService.mask("ENC:someciphertext"));
        assertEquals("****", encryptionService.mask("plaintext"));
    }

    @Test
    void nullPassthrough() {
        assertNull(encryptionService.encrypt(null));
        assertNull(encryptionService.decrypt(null));
    }

    @Test
    void decryptNonEncryptedReturnsAsIs() {
        String plain = "not-encrypted";
        assertEquals(plain, encryptionService.decrypt(plain));
    }

    @Test
    void emptyStringPassthrough() {
        assertEquals("", encryptionService.encrypt(""));
        assertEquals("", encryptionService.decrypt(""));
    }
}
