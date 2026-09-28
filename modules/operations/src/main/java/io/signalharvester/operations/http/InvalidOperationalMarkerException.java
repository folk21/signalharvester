package io.signalharvester.operations.http;

/** Indicates that a tooling marker violates the bounded/sanitized operational marker contract. */
public final class InvalidOperationalMarkerException extends RuntimeException {
    public InvalidOperationalMarkerException(String message) {
        super(message);
    }
}
