package io.signalharvester.configuration.application;

import io.signalharvester.configuration.api.SourceId;

/** Signals that a source cannot be deleted while a monitoring profile references it. */
public final class SourceInUseException extends RuntimeException {
    public SourceInUseException(SourceId sourceId) {
        super("Source is used by a monitoring profile: " + sourceId.value());
    }
}
