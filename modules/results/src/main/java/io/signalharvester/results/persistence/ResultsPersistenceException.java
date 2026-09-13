package io.signalharvester.results.persistence;

/**
 * Signals a failure to persist the Results read model.
 */
public final class ResultsPersistenceException extends RuntimeException {

    public ResultsPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
