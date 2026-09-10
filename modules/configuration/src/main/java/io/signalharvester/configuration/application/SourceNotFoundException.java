package io.signalharvester.configuration.application;

import io.signalharvester.configuration.api.SourceId;

/**
 * Signals that a requested configured source does not exist.
 */
public final class SourceNotFoundException extends RuntimeException {

    private final SourceId sourceId;

    public SourceNotFoundException(SourceId sourceId) {
        super("Configured source not found: " + sourceId.value());
        this.sourceId = sourceId;
    }

    /**
     * Returns the missing source identifier.
     *
     * @return missing source identifier
     */
    public SourceId sourceId() {
        return sourceId;
    }
}
