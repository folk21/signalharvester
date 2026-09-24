package io.signalharvester.analysis.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persists and leases Analysis outbox records inside caller-owned JDBC transactions. */
public interface AnalysisOutboxStore {

    /** Appends one serialized terminal event atomically with Analysis state changes. */
    void append(AnalysisOutboxEntry entry);

    /** Reads the current unpublished backlog for operational telemetry. */
    AnalysisOutboxBacklog inspectBacklog();

    /** Claims a bounded batch for one dispatcher lease. */
    List<AnalysisOutboxEntry> claimBatch(Instant now, UUID leaseToken, Instant leaseExpiresAt, int limit);

    /** Extends one still-live leased outbox event before publication begins. */
    void renewLease(String eventId, UUID leaseToken, Instant renewedAt, Instant leaseExpiresAt);

    /** Marks one leased outbox event as published. */
    void markPublished(String eventId, UUID leaseToken, Instant publishedAt);

    /** Releases one still-live leased outbox event for a later retry while recording a bounded error. */
    void markFailed(
            String eventId, UUID leaseToken, Instant failedAt, Instant nextAttemptAt, String failureMessage);
}
