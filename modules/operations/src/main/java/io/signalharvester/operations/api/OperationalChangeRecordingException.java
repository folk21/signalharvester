package io.signalharvester.operations.api;

/** Signals that an operational change could not be durably journaled. */
public final class OperationalChangeRecordingException extends RuntimeException {
    public OperationalChangeRecordingException(String message, Throwable cause) {
        super(message, cause);
    }
}
