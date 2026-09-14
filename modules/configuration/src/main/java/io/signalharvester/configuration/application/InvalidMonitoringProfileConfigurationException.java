package io.signalharvester.configuration.application;

/** Signals invalid monitoring-profile configuration at the application boundary. */
public final class InvalidMonitoringProfileConfigurationException extends RuntimeException {
    public InvalidMonitoringProfileConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }

    public InvalidMonitoringProfileConfigurationException(String message) {
        super(message);
    }
}
