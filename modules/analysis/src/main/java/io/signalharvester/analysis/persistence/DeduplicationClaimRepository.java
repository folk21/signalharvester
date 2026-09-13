package io.signalharvester.analysis.persistence;

import io.signalharvester.analysis.model.NormalizedContentItem;
import java.time.Instant;

/**
 * Analysis-owned persistence boundary for profile-scoped logical item deduplication.
 * Calls require an active application-owned JDBC transaction.
 */
public interface DeduplicationClaimRepository {

    /**
     * Claims a normalized item for a monitoring profile inside the caller-owned transaction.
     *
     * @return {@code true} when this transaction created the first accepted claim
     */
    boolean tryClaim(NormalizedContentItem item, Instant seenAt);

    /** Records an observed duplicate inside the caller-owned processing transaction. */
    void recordDuplicate(NormalizedContentItem item, Instant seenAt);
}
