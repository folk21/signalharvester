package io.signalharvester.collection.source;

import io.signalharvester.configuration.api.SourceId;
import java.net.URI;
import java.util.Objects;

/**
 * Reports a transport-level failure while loading a configured external source.
 */
public final class SourceFetchException extends RuntimeException {

    private final SourceId sourceId;
    private final URI uri;

    /**
     * Creates a source-fetch failure with source provenance preserved for diagnostics.
     *
     * @param sourceId configured source identifier
     * @param uri external URI that was requested
     * @param message human-readable failure description
     */
    public SourceFetchException(SourceId sourceId, URI uri, String message) {
        super(message);
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.uri = Objects.requireNonNull(uri, "uri");
    }

    /**
     * Creates a source-fetch failure caused by a lower-level transport exception.
     *
     * @param sourceId configured source identifier
     * @param uri external URI that was requested
     * @param message human-readable failure description
     * @param cause underlying transport failure
     */
    public SourceFetchException(SourceId sourceId, URI uri, String message, Throwable cause) {
        super(message, cause);
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.uri = Objects.requireNonNull(uri, "uri");
    }

    /**
     * Returns the source whose fetch failed.
     *
     * @return configured source identifier
     */
    public SourceId sourceId() {
        return sourceId;
    }

    /**
     * Returns the URI involved in the failure.
     *
     * @return requested external URI
     */
    public URI uri() {
        return uri;
    }
}
