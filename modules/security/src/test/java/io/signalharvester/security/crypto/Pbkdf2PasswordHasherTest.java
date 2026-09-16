package io.signalharvester.security.crypto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Verifies local password hashing for feature {@code SECURITY.AUTHENTICATION}. */
class Pbkdf2PasswordHasherTest {

    private final Pbkdf2PasswordHasher hasher = new Pbkdf2PasswordHasher(10_000);

    /** Hash the same password with independent salts and verify only the matching secret. */
    @Test
    void shouldHashWithRandomSaltAndVerifyPassword() {
        char[] password = "correct horse battery staple".toCharArray();

        String first = hasher.hash(password);
        String second = hasher.hash(password);

        assertNotEquals(first, second);
        assertTrue(hasher.matches(password, first));
        assertTrue(hasher.matches(password, second));
        assertFalse(hasher.matches("wrong password".toCharArray(), first));
    }

    /** Reject malformed encoded hashes without treating them as valid credentials. */
    @Test
    void shouldRejectMalformedStoredHash() {
        assertFalse(hasher.matches("password".toCharArray(), "not-a-supported-hash"));
        assertFalse(hasher.matches("password".toCharArray(), "pbkdf2-sha256$1$bad$bad"));
    }
}
