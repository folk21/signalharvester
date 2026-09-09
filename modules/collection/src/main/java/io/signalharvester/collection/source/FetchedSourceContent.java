package io.signalharvester.collection.source;

import io.signalharvester.configuration.api.SourceId;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Contains the raw payload and transport metadata produced by an external-source fetch.
 *
 * @param sourceId configured source identifier
 * @param requestedUri URI requested by the collection adapter
 * @param statusCode successful HTTP status code
 * @param contentType response content type when supplied by the source
 * @param body raw response body
 * @param fetchedAt timestamp recorded when the response was accepted
 */
public record FetchedSourceContent(
        SourceId sourceId,
        URI requestedUri,
        int statusCode,
        Optional<String> contentType,
        byte[] body,
        Instant fetchedAt) {

    public FetchedSourceContent {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(requestedUri, "requestedUri");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(fetchedAt, "fetchedAt");

        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalArgumentException("statusCode must be successful");
        }

        body = body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
