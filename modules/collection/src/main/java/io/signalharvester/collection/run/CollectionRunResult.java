package io.signalharvester.collection.run;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Captures one explicit collection-run identity, timing, aggregate status, and source outcomes.
 *
 * <p>The first run slice returns this state to the caller but does not persist run history yet.</p>
 *
 * @param collectionRunId stable identity for this execution and its Kafka correlation id
 * @param monitoringProfileId logical profile that initiated the run
 * @param informationCategory category applied to discovered raw items
 * @param startedAt run start timestamp
 * @param finishedAt run completion timestamp
 * @param status aggregate run status
 * @param sources source outcomes in deterministic configured-source order
 */
public record CollectionRunResult(
        String collectionRunId,
        String monitoringProfileId,
        String informationCategory,
        Instant startedAt,
        Instant finishedAt,
        CollectionRunStatus status,
        List<CollectionSourceResult> sources) {

    public CollectionRunResult {
        requireNonBlank(collectionRunId, "collectionRunId");
        requireNonBlank(monitoringProfileId, "monitoringProfileId");
        requireNonBlank(informationCategory, "informationCategory");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(finishedAt, "finishedAt");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(sources, "sources");
        if (finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("finishedAt must not be before startedAt");
        }
        sources = List.copyOf(sources);
    }

    /**
     * Returns the number of source payloads successfully published to Kafka.
     *
     * @return successful publication count
     */
    public long publishedCount() {
        return sources.stream().filter(source -> source.status() == CollectionSourceStatus.PUBLISHED).count();
    }

    /**
     * Returns the number of source operations that ended in fetch or publication failure.
     *
     * @return failed source count
     */
    public long failedCount() {
        return sources.size() - publishedCount();
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
