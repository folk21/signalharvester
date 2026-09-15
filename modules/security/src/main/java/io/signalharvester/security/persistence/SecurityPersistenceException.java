package io.signalharvester.security.persistence;

/** Signals an unexpected failure while accessing security-owned persistent state. */
public final class SecurityPersistenceException extends RuntimeException {
    public SecurityPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
