package io.signalharvester.operations.persistence;

/** Wraps persistence failures owned by the Operations module. */
public final class OperationalIntelligencePersistenceException extends RuntimeException {
    public OperationalIntelligencePersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
