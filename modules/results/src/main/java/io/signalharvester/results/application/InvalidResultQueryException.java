package io.signalharvester.results.application;

/** Indicates that a Results browsing request is structurally invalid or uses an incompatible cursor. */
public final class InvalidResultQueryException extends RuntimeException {

    public InvalidResultQueryException(String message) {
        super(message);
    }

    public InvalidResultQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
