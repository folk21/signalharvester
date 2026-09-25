package io.signalharvester.operations.assisted;

/** Provider transport/protocol failure during an explicit assisted-investigation request. */
public final class IncidentAnalystException extends RuntimeException {
    public IncidentAnalystException(String message) {
        super(message);
    }

    public IncidentAnalystException(String message, Throwable cause) {
        super(message, cause);
    }
}
