package io.signalharvester.configuration.application;

/**
 * Signals invalid source configuration that cannot satisfy the configuration-domain invariants.
 */
public final class InvalidSourceConfigurationException extends RuntimeException {

    public InvalidSourceConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
