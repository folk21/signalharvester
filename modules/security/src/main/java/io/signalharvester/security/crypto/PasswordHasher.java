package io.signalharvester.security.crypto;

/** One-way password hashing boundary used by local account authentication. */
public interface PasswordHasher {
    /** Produces a self-describing non-reversible password hash. */
    String hash(char[] password);

    /** Verifies a password against a previously stored hash. */
    boolean matches(char[] password, String encodedHash);
}
