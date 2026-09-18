package io.signalharvester.results.application;

/** Expected operator-recovery failure with an HTTP-mappable category. */
public final class DeadLetterRecoveryException extends RuntimeException {

    /** Stable recovery failure categories used by the HTTP adapter. */
    public enum Reason {
        NOT_FOUND,
        CONFIRMATION_FAILED,
        INVALID_RECORD,
        BUSY,
        KAFKA_UNAVAILABLE,
        REPLAY_FAILED
    }

    private final Reason reason;

    public DeadLetterRecoveryException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public DeadLetterRecoveryException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    /** Returns the stable reason that determines the administrative HTTP status. */
    public Reason reason() {
        return reason;
    }
}
