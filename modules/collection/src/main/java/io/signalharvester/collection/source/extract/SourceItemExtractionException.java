package io.signalharvester.collection.source.extract;

import io.signalharvester.configuration.api.SourceId;
import java.util.Objects;

/** Raised when a fetched source response cannot be converted into collection items. */
public final class SourceItemExtractionException extends RuntimeException {

    private final SourceId sourceId;

    public SourceItemExtractionException(SourceId sourceId, String message) {
        super(message);
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
    }

    public SourceItemExtractionException(SourceId sourceId, String message, Throwable cause) {
        super(message, cause);
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
    }

    public SourceId sourceId() {
        return sourceId;
    }
}
