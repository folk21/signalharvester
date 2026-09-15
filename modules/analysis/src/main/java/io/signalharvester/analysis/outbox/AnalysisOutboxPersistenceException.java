package io.signalharvester.analysis.outbox;

/** Signals a persistence failure in the Analysis transactional outbox. */
public final class AnalysisOutboxPersistenceException extends RuntimeException {

    public AnalysisOutboxPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
