package io.signalharvester.security.application;

/** Signals a case-insensitive username uniqueness conflict. */
public final class UsernameAlreadyExistsException extends RuntimeException {
    public UsernameAlreadyExistsException(String username) {
        super("Username already exists: " + username);
    }
}
