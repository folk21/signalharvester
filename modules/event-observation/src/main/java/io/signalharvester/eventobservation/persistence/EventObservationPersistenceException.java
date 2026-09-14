package io.signalharvester.eventobservation.persistence;

/** Indicates failure to persist or query the diagnostic event-observation projection. */
public final class EventObservationPersistenceException extends RuntimeException {
    public EventObservationPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
