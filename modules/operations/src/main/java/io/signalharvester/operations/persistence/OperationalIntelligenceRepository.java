package io.signalharvester.operations.persistence;

import io.signalharvester.operations.api.OperationalChangeRecord;
import io.signalharvester.operations.assisted.AutomaticInvestigationTrigger;
import io.signalharvester.operations.assisted.ClaimedAutomaticInvestigation;
import io.signalharvester.operations.model.HealthSnapshot;
import io.signalharvester.operations.model.IncidentAssessment;
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
    Optional<HealthSnapshot> findSnapshot(UUID snapshotId);
    List<HealthSnapshot> findRecentSnapshotsBefore(Instant instant, int limit);
    boolean tryAcquireHealthSamplingLock();
    Optional<HealthSnapshot> findLatestSnapshotAtOrBefore(Instant instant);
    Optional<HealthSnapshot> findEarliestSnapshotAtOrAfter(Instant instant);
    void insertIncidentAssessment(IncidentAssessment assessment);
    List<IncidentAssessment> findRecentIncidentAssessments(int limit);
    void deleteIncidentAssessmentsBeyond(int keepCount);
    boolean tryAcquireAutomaticInvestigationPlanningLock();
    boolean insertAutomaticInvestigationTrigger(AutomaticInvestigationTrigger trigger);
    Optional<AutomaticInvestigationTrigger> findLatestAutomaticInvestigationTrigger();
    List<AutomaticInvestigationTrigger> findRecentAutomaticInvestigationTriggers(int limit);
    Optional<ClaimedAutomaticInvestigation> claimAutomaticInvestigationTrigger(
            Instant now, UUID leaseToken, Instant leaseExpiresAt);
    boolean renewAutomaticInvestigationTriggerLease(
            UUID triggerId, UUID leaseToken, Instant renewedAt, Instant leaseExpiresAt);
    void completeAutomaticInvestigationTrigger(
            UUID triggerId, UUID leaseToken, Instant completedAt, UUID assessmentId);
    void retryAutomaticInvestigationTrigger(
            UUID triggerId, UUID leaseToken, Instant failedAt, Instant nextAttemptAt, String lastError);
    void exhaustAutomaticInvestigationTrigger(
            UUID triggerId, UUID leaseToken, Instant failedAt, String lastError);
}
