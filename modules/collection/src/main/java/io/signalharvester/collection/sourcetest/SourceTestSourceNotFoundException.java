package io.signalharvester.collection.sourcetest;

import io.signalharvester.configuration.api.SourceId;
import java.util.Objects;

/** Raised when source testing references a configured source that does not exist. */
public final class SourceTestSourceNotFoundException extends RuntimeException {

    private final SourceId sourceId;

    public SourceTestSourceNotFoundException(SourceId sourceId) {
        super("Configured source not found: " + Objects.requireNonNull(sourceId, "sourceId").value());
        this.sourceId = sourceId;
    }

    /** Returns the missing persisted source identity. */
    public SourceId sourceId() {
        return sourceId;
    }
}
