package io.signalharvester.collection.run;

import io.signalharvester.configuration.api.SourceId;
import java.util.Objects;
import java.util.Optional;

/**
 * Captures the terminal result for one source in a collection run without leaking transport types.
 *
 * @param sourceId configured source identifier
 * @param status terminal source outcome
 * @param rawItemId stable raw-item identity when content was fetched
 * @param eventId acknowledged Kafka event identifier when publication succeeded
 * @param failureMessage diagnostic failure summary when the source did not publish successfully
 */
public record CollectionSourceResult(
        SourceId sourceId,
        CollectionSourceStatus status,
        Optional<String> rawItemId,
        Optional<String> eventId,
        Optional<String> failureMessage) {

    public CollectionSourceResult {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(rawItemId, "rawItemId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(failureMessage, "failureMessage");
        rawItemId.ifPresent(value -> requireNonBlank(value, "rawItemId"));
        eventId.ifPresent(value -> requireNonBlank(value, "eventId"));
        failureMessage.ifPresent(value -> requireNonBlank(value, "failureMessage"));

        switch (status) {
            case PUBLISHED -> {
                if (rawItemId.isEmpty() || eventId.isEmpty() || failureMessage.isPresent()) {
                    throw new IllegalArgumentException(
                            "published source result requires rawItemId/eventId and no failureMessage");
                }
            }
            case FETCH_FAILED -> {
                if (rawItemId.isPresent() || eventId.isPresent() || failureMessage.isEmpty()) {
                    throw new IllegalArgumentException(
                            "fetch failure requires only failureMessage");
                }
            }
            case PUBLICATION_FAILED -> {
                if (rawItemId.isEmpty() || eventId.isPresent() || failureMessage.isEmpty()) {
                    throw new IllegalArgumentException(
                            "publication failure requires rawItemId/failureMessage and no eventId");
                }
            }
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
