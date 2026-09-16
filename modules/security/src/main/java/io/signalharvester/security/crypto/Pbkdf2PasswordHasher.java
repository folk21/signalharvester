package io.signalharvester.security.crypto;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** PBKDF2-HMAC-SHA256 password hasher with per-password random salts and encoded work factor. */
@Singleton
public final class Pbkdf2PasswordHasher implements PasswordHasher {
    private static final String PREFIX = "pbkdf2-sha256";
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final int MAX_PASSWORD_CHARS = 1024;

    private final SecureRandom secureRandom = new SecureRandom();
    private final int iterations;

    public Pbkdf2PasswordHasher(
            @Value("${signalharvester.security.password-hashing.iterations:600000}") int iterations) {
        if (iterations < 10_000) {
            throw new IllegalArgumentException("Password hashing iterations must be at least 10000");
        }
        this.iterations = iterations;
    }

    @Override
    public String hash(char[] password) {
        validatePassword(password);
        byte[] salt = new byte[SALT_BYTES];
        secureRandom.nextBytes(salt);
        byte[] derived = derive(password, salt, iterations);
        try {
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return PREFIX + "$" + iterations + "$" + encoder.encodeToString(salt) + "$" + encoder.encodeToString(derived);
        } finally {
            Arrays.fill(derived, (byte) 0);
        }
    }

    @Override
    public boolean matches(char[] password, String encodedHash) {
        validatePassword(password);
        if (encodedHash == null) {
            return false;
        }
        String[] parts = encodedHash.split("\\$", -1);
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            int storedIterations = Integer.parseInt(parts[1]);
            if (storedIterations < 10_000) {
                return false;
            }
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] salt = decoder.decode(parts[2]);
            byte[] expected = decoder.decode(parts[3]);
            byte[] actual = derive(password, salt, storedIterations);
            try {
                return MessageDigest.isEqual(expected, actual);
            } finally {
                Arrays.fill(actual, (byte) 0);
                Arrays.fill(expected, (byte) 0);
                Arrays.fill(salt, (byte) 0);
            }
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("PBKDF2-HMAC-SHA256 is unavailable", exception);
        } finally {
            spec.clearPassword();
        }
    }

    private static void validatePassword(char[] password) {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("Password must not be empty");
        }
        if (password.length > MAX_PASSWORD_CHARS) {
            throw new IllegalArgumentException("Password exceeds the supported length");
        }
    }
}
