package io.signalharvester.collection.run;

import io.signalharvester.configuration.api.SourceId;
import java.util.Objects;
import java.util.Optional;

/**
 * Captures one terminal source/item outcome without leaking transport or parser types.
 *
 * <p>REST/HTML sources normally contribute one outcome. RSS/Atom sources contribute one outcome per
 * extracted entry; an empty valid feed contributes one {@link CollectionSourceStatus#NO_ITEMS} outcome.</p>
 *
 * @param sourceId configured source identifier
 * @param status terminal source/item outcome
 * @param rawItemId stable raw-item identity when one semantic item reached publication
 * @param eventId acknowledged Kafka event identifier when publication succeeded
 * @param failureMessage diagnostic failure summary when fetch/extraction/publication failed
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
                            "published outcome requires rawItemId/eventId and no failureMessage");
                }
            }
            case NO_ITEMS -> {
                if (rawItemId.isPresent() || eventId.isPresent() || failureMessage.isPresent()) {
                    throw new IllegalArgumentException("no-items outcome must not contain item or failure metadata");
                }
            }
            case FETCH_FAILED, EXTRACTION_FAILED -> {
                if (rawItemId.isPresent() || eventId.isPresent() || failureMessage.isEmpty()) {
                    throw new IllegalArgumentException(
                            status + " outcome requires only failureMessage");
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

    /** Returns whether this outcome represents a source/item failure. */
    public boolean failed() {
        return status == CollectionSourceStatus.FETCH_FAILED
                || status == CollectionSourceStatus.EXTRACTION_FAILED
                || status == CollectionSourceStatus.PUBLICATION_FAILED;
    }

    private static void requireNonBlank(String value, String name) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
