package io.signalharvester.collection.source;

import io.signalharvester.configuration.api.SourceId;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Reports a transport-level failure while loading a configured external source.
 *
 * <p>HTTP status failures retain status and raw {@code Retry-After} metadata so later retry policy
 * can make decisions without depending on Micronaut HTTP exception types.</p>
 */
public final class SourceFetchException extends RuntimeException {

    private final SourceId sourceId;
    private final URI uri;
    private final OptionalInt statusCode;
    private final Optional<String> retryAfter;

    /**
     * Creates a source-fetch failure without an HTTP response status.
     *
     * @param sourceId configured source identifier
     * @param uri external URI that was requested
     * @param message human-readable failure description
     */
    public SourceFetchException(SourceId sourceId, URI uri, String message) {
        super(message);
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.uri = Objects.requireNonNull(uri, "uri");
        this.statusCode = OptionalInt.empty();
        this.retryAfter = Optional.empty();
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
        this.statusCode = OptionalInt.empty();
        this.retryAfter = Optional.empty();
    }

    /**
     * Creates a source-fetch failure for a received non-success HTTP response.
     *
     * @param sourceId configured source identifier
     * @param uri external URI that was requested
     * @param statusCode received HTTP status code
     * @param retryAfter raw Retry-After header when supplied by the source
     */
    public SourceFetchException(
            SourceId sourceId, URI uri, int statusCode, Optional<String> retryAfter) {
        super("External source returned HTTP status " + statusCode);
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.uri = Objects.requireNonNull(uri, "uri");
        this.statusCode = OptionalInt.of(statusCode);
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
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

    /**
     * Returns the received HTTP status when the failure came from a response.
     *
     * @return response status or an empty value for transport failures
     */
    public OptionalInt statusCode() {
        return statusCode;
    }

    /**
     * Returns the raw Retry-After response header when supplied.
     *
     * @return retry hint from the external source
     */
    public Optional<String> retryAfter() {
        return retryAfter;
    }
}
