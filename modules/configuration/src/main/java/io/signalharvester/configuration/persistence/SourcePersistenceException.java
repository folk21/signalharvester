package io.signalharvester.configuration.persistence;

/**
 * Wraps unexpected database failures inside the configuration module boundary.
 */
public final class SourcePersistenceException extends RuntimeException {

    public SourcePersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
