package io.signalharvester.security.application;

/** Signals invalid identity or role configuration at the application boundary. */
public final class InvalidUserConfigurationException extends RuntimeException {
    public InvalidUserConfigurationException(String message) {
        super(message);
    }

    public InvalidUserConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
