package io.signalharvester.analysis.persistence;

/**
 * Wraps unexpected analysis-owned database failures without leaking JDBC exceptions.
 */
public final class AnalysisPersistenceException extends RuntimeException {

    public AnalysisPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
