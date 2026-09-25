package io.signalharvester.operations.persistence;

import io.signalharvester.operations.api.OperationalChangeRecord;
import io.signalharvester.operations.model.HealthSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for operational change history and Health Snapshots. */
public interface OperationalIntelligenceRepository {
    void insertChange(OperationalChangeRecord change);
    List<OperationalChangeRecord> findRecentChanges(int limit);
    Optional<OperationalChangeRecord> findChange(UUID changeId);
    List<OperationalChangeRecord> findChangesBetween(Instant fromInclusive, Instant toInclusive, int limit);
    void insertSnapshot(HealthSnapshot snapshot);
    void deleteSnapshotsBeyond(int keepCount);
    Optional<HealthSnapshot> findLatestSnapshot();
    List<HealthSnapshot> findRecentSnapshotsBefore(Instant instant, int limit);
    boolean tryAcquireHealthSamplingLock();
    Optional<HealthSnapshot> findLatestSnapshotAtOrBefore(Instant instant);
    Optional<HealthSnapshot> findEarliestSnapshotAtOrAfter(Instant instant);
}
