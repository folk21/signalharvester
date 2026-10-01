package io.signalharvester.operations.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.signalharvester.common.persistence.SqlResources;
import io.signalharvester.operations.api.OperationalChangeCategory;
import io.signalharvester.operations.api.OperationalChangeOutcome;
import io.signalharvester.operations.api.OperationalChangeRecord;
import io.signalharvester.operations.api.OperationalChangeSource;
import io.signalharvester.operations.api.OperationalChangeTargetType;
import io.signalharvester.operations.assisted.AutomaticInvestigationTrigger;
import io.signalharvester.operations.assisted.AutomaticInvestigationTriggerState;
import io.signalharvester.operations.assisted.AutomaticInvestigationTriggerType;
import io.signalharvester.operations.assisted.ClaimedAutomaticInvestigation;
import io.signalharvester.operations.alert.HumanAttentionAlert;
import io.signalharvester.operations.alert.HumanAttentionAlertReason;
import io.signalharvester.operations.alert.HumanAttentionAlertSeverity;
import io.signalharvester.operations.alert.HumanAttentionAlertState;
import io.signalharvester.operations.model.HealthAnomaly;
import io.signalharvester.operations.model.HealthSnapshot;
import io.signalharvester.operations.model.HealthStatus;
import io.signalharvester.operations.model.IncidentAssessment;
import io.signalharvester.operations.model.IncidentAssessmentSource;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/** Jdbi adapter for Operations-owned change-journal and Health Snapshot persistence. */
@Singleton
public final class JdbiOperationalIntelligenceRepository implements OperationalIntelligenceRepository {
    private static final String SQL_PATH = "operations";
    private static final String INSERT_CHANGE = SqlResources.load(SQL_PATH, "insert-change");
    private static final String FIND_RECENT_CHANGES = SqlResources.load(SQL_PATH, "find-recent-changes");
    private static final String FIND_CHANGE = SqlResources.load(SQL_PATH, "find-change");
    private static final String FIND_CHANGES_BETWEEN = SqlResources.load(SQL_PATH, "find-changes-between");
    private static final String INSERT_SNAPSHOT = SqlResources.load(SQL_PATH, "insert-snapshot");
    private static final String DELETE_OLD_SNAPSHOTS = SqlResources.load(SQL_PATH, "delete-old-snapshots");
    private static final String FIND_LATEST_SNAPSHOT = SqlResources.load(SQL_PATH, "find-latest-snapshot");
    private static final String FIND_SNAPSHOT = SqlResources.load(SQL_PATH, "find-snapshot");
    private static final String FIND_RECENT_SNAPSHOTS_BEFORE = SqlResources.load(SQL_PATH, "find-recent-snapshots-before");
    private static final String TRY_HEALTH_SAMPLING_LOCK = SqlResources.load(SQL_PATH, "try-health-sampling-lock");
    private static final String FIND_SNAPSHOT_BEFORE = SqlResources.load(SQL_PATH, "find-snapshot-before");
    private static final String FIND_SNAPSHOT_AFTER = SqlResources.load(SQL_PATH, "find-snapshot-after");
    private static final String ACQUIRE_HUMAN_ATTENTION_ALERT_DECISION_LOCK =
            SqlResources.load(SQL_PATH, "acquire-human-attention-alert-decision-lock");
    private static final String INSERT_HUMAN_ATTENTION_ALERT =
            SqlResources.load(SQL_PATH, "insert-human-attention-alert");
    private static final String FIND_OPEN_HUMAN_ATTENTION_ALERT =
            SqlResources.load(SQL_PATH, "find-open-human-attention-alert");
    private static final String FIND_LATEST_RESOLVED_HUMAN_ATTENTION_ALERT =
            SqlResources.load(SQL_PATH, "find-latest-resolved-human-attention-alert");
    private static final String FIND_RECENT_HUMAN_ATTENTION_ALERTS =
            SqlResources.load(SQL_PATH, "find-recent-human-attention-alerts");
    private static final String UPDATE_HUMAN_ATTENTION_ALERT_OBSERVATION =
            SqlResources.load(SQL_PATH, "update-human-attention-alert-observation");
    private static final String RESOLVE_HUMAN_ATTENTION_ALERT =
            SqlResources.load(SQL_PATH, "resolve-human-attention-alert");
    private static final String ATTACH_ASSESSMENT_TO_HUMAN_ATTENTION_ALERT =
            SqlResources.load(SQL_PATH, "attach-assessment-to-human-attention-alert");
    private static final String DELETE_OLD_HUMAN_ATTENTION_ALERTS =
            SqlResources.load(SQL_PATH, "delete-old-human-attention-alerts");
    private static final String INSERT_INCIDENT_ASSESSMENT = SqlResources.load(SQL_PATH, "insert-incident-assessment");
    private static final String FIND_RECENT_INCIDENT_ASSESSMENTS = SqlResources.load(SQL_PATH, "find-recent-incident-assessments");
    private static final String DELETE_OLD_INCIDENT_ASSESSMENTS = SqlResources.load(SQL_PATH, "delete-old-incident-assessments");
    private static final String TRY_AUTOMATIC_INVESTIGATION_PLANNING_LOCK =
            SqlResources.load(SQL_PATH, "try-automatic-investigation-planning-lock");
    private static final String INSERT_AUTOMATIC_INVESTIGATION_TRIGGER =
            SqlResources.load(SQL_PATH, "insert-automatic-investigation-trigger");
    private static final String FIND_LATEST_AUTOMATIC_INVESTIGATION_TRIGGER =
            SqlResources.load(SQL_PATH, "find-latest-automatic-investigation-trigger");
    private static final String FIND_RECENT_AUTOMATIC_INVESTIGATION_TRIGGERS =
            SqlResources.load(SQL_PATH, "find-recent-automatic-investigation-triggers");
    private static final String CLAIM_AUTOMATIC_INVESTIGATION_TRIGGER =
            SqlResources.load(SQL_PATH, "claim-automatic-investigation-trigger");
    private static final String RENEW_AUTOMATIC_INVESTIGATION_TRIGGER =
            SqlResources.load(SQL_PATH, "renew-automatic-investigation-trigger");
    private static final String COMPLETE_AUTOMATIC_INVESTIGATION_TRIGGER =
            SqlResources.load(SQL_PATH, "complete-automatic-investigation-trigger");
    private static final String RETRY_AUTOMATIC_INVESTIGATION_TRIGGER =
            SqlResources.load(SQL_PATH, "fail-automatic-investigation-trigger");
    private static final String EXHAUST_AUTOMATIC_INVESTIGATION_TRIGGER =
            SqlResources.load(SQL_PATH, "exhaust-automatic-investigation-trigger");

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};
    private static final TypeReference<Map<String, Double>> DOUBLE_MAP = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<HealthAnomaly>> HEALTH_ANOMALY_LIST = new TypeReference<>() {};
    private static final TypeReference<List<UUID>> UUID_LIST = new TypeReference<>() {};

    private final Jdbi jdbi;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JdbiOperationalIntelligenceRepository(@Named("default") Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public void insertChange(OperationalChangeRecord change) {
        executeVoid("Failed to record operational change", handle -> handle.createUpdate(INSERT_CHANGE)
                .bind("changeId", change.id())
                .bind("changedAt", Timestamp.from(change.changedAt()))
                .bind("category", change.category().name())
                .bind("targetType", change.targetType().name())
                .bind("targetId", change.targetId())
                .bind("beforeState", writeJson(change.beforeState()))
                .bind("afterState", writeJson(change.afterState()))
                .bind("outcome", change.outcome().name())
                .bind("changeSource", change.source().name())
                .bind("actorId", change.actorId())
                .bind("correlationId", change.correlationId())
                .bind("traceId", change.traceId())
                .bind("applicationVersion", change.applicationVersion())
                .execute());
    }

    @Override
    public List<OperationalChangeRecord> findRecentChanges(int limit) {
        return execute("Failed to list operational changes", handle -> handle.createQuery(FIND_RECENT_CHANGES)
                .bind("limit", limit)
                .map((rows, context) -> mapChange(rows))
                .list());
    }

    @Override
    public Optional<OperationalChangeRecord> findChange(UUID changeId) {
        return execute("Failed to find operational change", handle -> handle.createQuery(FIND_CHANGE)
                .bind("changeId", changeId)
                .map((rows, context) -> mapChange(rows))
                .findFirst());
    }

    @Override
    public List<OperationalChangeRecord> findChangesBetween(Instant fromInclusive, Instant toInclusive, int limit) {
        return execute("Failed to query operational changes", handle -> handle.createQuery(FIND_CHANGES_BETWEEN)
                .bind("fromInclusive", Timestamp.from(fromInclusive))
                .bind("toInclusive", Timestamp.from(toInclusive))
                .bind("limit", limit)
                .map((rows, context) -> mapChange(rows))
                .list());
    }

    @Override
    public void insertSnapshot(HealthSnapshot snapshot) {
        executeVoid("Failed to record Health Snapshot", handle -> handle.createUpdate(INSERT_SNAPSHOT)
                .bind("snapshotId", snapshot.id())
                .bind("generatedAt", Timestamp.from(snapshot.generatedAt()))
                .bind("windowStartedAt", Timestamp.from(snapshot.windowStartedAt()))
                .bind("windowEndedAt", Timestamp.from(snapshot.windowEndedAt()))
                .bind("overallStatus", snapshot.overallStatus().name())
                .bind("healthScore", snapshot.healthScore())
                .bind("policyVersion", snapshot.policyVersion())
                .bind("componentStatuses", writeJson(snapshot.componentStatuses()))
                .bind("signalValues", writeJson(snapshot.signalValues()))
                .bind("anomalyCandidates", writeJson(snapshot.anomalyCandidates()))
                .bind("anomalyDetails", writeJson(snapshot.anomalyDetails()))
                .bind("recentChangeIds", writeJson(snapshot.recentChangeIds()))
                .bind("applicationVersion", snapshot.applicationVersion())
                .bind("evidenceComplete", snapshot.evidenceComplete())
                .bind("unknownReasons", writeJson(snapshot.unknownReasons()))
                .execute());
    }

    @Override
    public void deleteSnapshotsBeyond(int keepCount) {
        executeVoid("Failed to enforce Health Snapshot retention", handle -> handle.createUpdate(DELETE_OLD_SNAPSHOTS)
                .bind("keepCount", keepCount)
                .execute());
    }

    @Override
    public Optional<HealthSnapshot> findLatestSnapshot() {
        return execute("Failed to read latest Health Snapshot", handle -> handle.createQuery(FIND_LATEST_SNAPSHOT)
                .map((rows, context) -> mapSnapshot(rows))
                .findFirst());
    }

    @Override
    public Optional<HealthSnapshot> findSnapshot(UUID snapshotId) {
        return execute("Failed to read Health Snapshot", handle -> handle.createQuery(FIND_SNAPSHOT)
                .bind("snapshotId", snapshotId)
                .map((rows, context) -> mapSnapshot(rows))
                .findFirst());
    }

    @Override
    public List<HealthSnapshot> findRecentSnapshotsBefore(Instant instant, int limit) {
        return execute("Failed to read rolling Health Snapshot baseline", handle -> handle.createQuery(FIND_RECENT_SNAPSHOTS_BEFORE)
                .bind("instant", Timestamp.from(instant))
                .bind("limit", limit)
                .map((rows, context) -> mapSnapshot(rows))
                .list());
    }

    @Override
    public boolean tryAcquireHealthSamplingLock() {
        return execute("Failed to acquire Health Snapshot sampling lock", handle -> handle.createQuery(TRY_HEALTH_SAMPLING_LOCK)
                .mapTo(Boolean.class)
                .one());
    }

    @Override
    public Optional<HealthSnapshot> findLatestSnapshotAtOrBefore(Instant instant) {
        return execute("Failed to read Health Snapshot before change", handle -> handle.createQuery(FIND_SNAPSHOT_BEFORE)
                .bind("instant", Timestamp.from(instant))
                .map((rows, context) -> mapSnapshot(rows))
                .findFirst());
    }

    @Override
    public Optional<HealthSnapshot> findEarliestSnapshotAtOrAfter(Instant instant) {
        return execute("Failed to read Health Snapshot after change", handle -> handle.createQuery(FIND_SNAPSHOT_AFTER)
                .bind("instant", Timestamp.from(instant))
                .map((rows, context) -> mapSnapshot(rows))
                .findFirst());
    }


    @Override
    public void acquireHumanAttentionAlertDecisionLock() {
        executeVoid("Failed to acquire human-attention alert decision lock", handle ->
                handle.createQuery(ACQUIRE_HUMAN_ATTENTION_ALERT_DECISION_LOCK)
                        .map((rows, context) -> {
                            rows.getObject(1);
                            return Boolean.TRUE;
                        })
                        .one());
    }

    @Override
    public void insertHumanAttentionAlert(HumanAttentionAlert alert) {
        executeVoid("Failed to insert human-attention alert", handle -> handle.createUpdate(INSERT_HUMAN_ATTENTION_ALERT)
                .bind("alertId", alert.id())
                .bind("state", alert.state().name())
                .bind("severity", alert.severity().name())
                .bind("reason", alert.reason().name())
                .bind("policyVersion", alert.policyVersion())
                .bind("openedAt", Timestamp.from(alert.openedAt()))
                .bind("lastObservedAt", Timestamp.from(alert.lastObservedAt()))
                .bind("resolvedAt", alert.resolvedAt() == null ? null : Timestamp.from(alert.resolvedAt()))
                .bind("firstSnapshotId", alert.firstSnapshotId())
                .bind("latestSnapshotId", alert.latestSnapshotId())
                .bind("latestHealthStatus", alert.latestHealthStatus().name())
                .bind("latestHealthScore", alert.latestHealthScore())
                .bind("latestAssessmentId", alert.latestAssessmentId())
                .bind("modelAttentionSuggested", alert.modelAttentionSuggested())
                .execute());
    }

    @Override
    public Optional<HumanAttentionAlert> findOpenHumanAttentionAlert() {
        return execute("Failed to read open human-attention alert", handle -> handle.createQuery(FIND_OPEN_HUMAN_ATTENTION_ALERT)
                .map((rows, context) -> mapHumanAttentionAlert(rows))
                .findFirst());
    }

    @Override
    public Optional<HumanAttentionAlert> findLatestResolvedHumanAttentionAlert() {
        return execute("Failed to read latest resolved human-attention alert", handle -> handle.createQuery(FIND_LATEST_RESOLVED_HUMAN_ATTENTION_ALERT)
                .map((rows, context) -> mapHumanAttentionAlert(rows))
                .findFirst());
    }

    @Override
    public List<HumanAttentionAlert> findRecentHumanAttentionAlerts(int limit) {
        return execute("Failed to list human-attention alerts", handle -> handle.createQuery(FIND_RECENT_HUMAN_ATTENTION_ALERTS)
                .bind("limit", limit)
                .map((rows, context) -> mapHumanAttentionAlert(rows))
                .list());
    }

    @Override
    public void updateHumanAttentionAlertObservation(
            UUID alertId, HumanAttentionAlertSeverity severity, HumanAttentionAlertReason reason,
            Instant observedAt, UUID snapshotId, HealthStatus healthStatus, int healthScore) {
        executeExactAlertUpdate("update human-attention alert observation", handle -> handle.createUpdate(UPDATE_HUMAN_ATTENTION_ALERT_OBSERVATION)
                .bind("alertId", alertId)
                .bind("severity", severity.name())
                .bind("reason", reason.name())
                .bind("observedAt", Timestamp.from(observedAt))
                .bind("snapshotId", snapshotId)
                .bind("healthStatus", healthStatus.name())
                .bind("healthScore", healthScore)
                .execute());
    }

    @Override
    public void resolveHumanAttentionAlert(
            UUID alertId, Instant resolvedAt, UUID snapshotId, HealthStatus healthStatus, int healthScore) {
        executeExactAlertUpdate("resolve human-attention alert", handle -> handle.createUpdate(RESOLVE_HUMAN_ATTENTION_ALERT)
                .bind("alertId", alertId)
                .bind("resolvedAt", Timestamp.from(resolvedAt))
                .bind("snapshotId", snapshotId)
                .bind("healthStatus", healthStatus.name())
                .bind("healthScore", healthScore)
                .execute());
    }

    @Override
    public boolean attachAssessmentToHumanAttentionAlert(
            UUID alertId, UUID assessmentId, boolean modelAttentionSuggested) {
        return execute("Failed to attach assessment to human-attention alert", handle ->
                handle.createUpdate(ATTACH_ASSESSMENT_TO_HUMAN_ATTENTION_ALERT)
                        .bind("alertId", alertId)
                        .bind("assessmentId", assessmentId)
                        .bind("modelAttentionSuggested", modelAttentionSuggested)
                        .execute() == 1);
    }

    @Override
    public void deleteResolvedHumanAttentionAlertsBeyond(int keepCount) {
        executeVoid("Failed to enforce human-attention alert retention", handle -> handle.createUpdate(DELETE_OLD_HUMAN_ATTENTION_ALERTS)
                .bind("keepCount", keepCount)
                .execute());
    }

    @Override
    public void insertIncidentAssessment(IncidentAssessment assessment) {
        executeVoid("Failed to record incident assessment", handle -> handle.createUpdate(INSERT_INCIDENT_ASSESSMENT)
                .bind("assessmentId", assessment.id())
                .bind("snapshotId", assessment.snapshotId())
                .bind("createdAt", Timestamp.from(assessment.createdAt()))
                .bind("source", assessment.source().name())
                .bind("provider", assessment.provider())
                .bind("model", assessment.model())
                .bind("summary", assessment.summary())
                .bind("suspectedSubsystems", writeJson(assessment.suspectedSubsystems()))
                .bind("confidence", assessment.confidence())
                .bind("observations", writeJson(assessment.observations()))
                .bind("hypotheses", writeJson(assessment.hypotheses()))
                .bind("evidenceReferences", writeJson(assessment.evidenceReferences()))
                .bind("recommendedChecks", writeJson(assessment.recommendedChecks()))
                .bind("humanAttentionSuggested", assessment.humanAttentionSuggested())
                .execute());
    }

    @Override
    public List<IncidentAssessment> findRecentIncidentAssessments(int limit) {
        return execute("Failed to list incident assessments", handle -> handle.createQuery(FIND_RECENT_INCIDENT_ASSESSMENTS)
                .bind("limit", limit)
                .map((rows, context) -> mapIncidentAssessment(rows))
                .list());
    }

    @Override
    public void deleteIncidentAssessmentsBeyond(int keepCount) {
        executeVoid("Failed to enforce incident-assessment retention", handle -> handle.createUpdate(DELETE_OLD_INCIDENT_ASSESSMENTS)
                .bind("keepCount", keepCount)
                .execute());
    }

    @Override
    public boolean tryAcquireAutomaticInvestigationPlanningLock() {
        return execute("Failed to acquire automatic-investigation planning lock", handle ->
                handle.createQuery(TRY_AUTOMATIC_INVESTIGATION_PLANNING_LOCK)
                        .mapTo(Boolean.class)
                        .one());
    }

    @Override
    public boolean insertAutomaticInvestigationTrigger(AutomaticInvestigationTrigger trigger) {
        return execute("Failed to insert automatic-investigation trigger", handle ->
                handle.createUpdate(INSERT_AUTOMATIC_INVESTIGATION_TRIGGER)
                        .bind("triggerId", trigger.id())
                        .bind("snapshotId", trigger.snapshotId())
                        .bind("triggerType", trigger.type().name())
                        .bind("createdAt", Timestamp.from(trigger.createdAt()))
                        .bind("nextAttemptAt", Timestamp.from(trigger.nextAttemptAt()))
                        .execute() == 1);
    }

    @Override
    public Optional<AutomaticInvestigationTrigger> findLatestAutomaticInvestigationTrigger() {
        return execute("Failed to read latest automatic-investigation trigger", handle ->
                handle.createQuery(FIND_LATEST_AUTOMATIC_INVESTIGATION_TRIGGER)
                        .map((rows, context) -> mapAutomaticInvestigationTrigger(rows))
                        .findFirst());
    }

    @Override
    public List<AutomaticInvestigationTrigger> findRecentAutomaticInvestigationTriggers(int limit) {
        return execute("Failed to list automatic-investigation triggers", handle ->
                handle.createQuery(FIND_RECENT_AUTOMATIC_INVESTIGATION_TRIGGERS)
                        .bind("limit", limit)
                        .map((rows, context) -> mapAutomaticInvestigationTrigger(rows))
                        .list());
    }

    @Override
    public Optional<ClaimedAutomaticInvestigation> claimAutomaticInvestigationTrigger(
            Instant now, UUID leaseToken, Instant leaseExpiresAt) {
        return execute("Failed to claim automatic-investigation trigger", handle ->
                handle.createQuery(CLAIM_AUTOMATIC_INVESTIGATION_TRIGGER)
                        .bind("now", Timestamp.from(now))
                        .bind("leaseToken", leaseToken)
                        .bind("leaseExpiresAt", Timestamp.from(leaseExpiresAt))
                        .map((rows, context) -> new ClaimedAutomaticInvestigation(
                                rows.getObject("trigger_id", UUID.class),
                                rows.getObject("snapshot_id", UUID.class),
                                AutomaticInvestigationTriggerType.valueOf(rows.getString("trigger_type")),
                                rows.getInt("attempt_count"),
                                rows.getObject("lease_token", UUID.class),
                                rows.getTimestamp("lease_expires_at").toInstant()))
                        .findFirst());
    }

    @Override
    public boolean renewAutomaticInvestigationTriggerLease(
            UUID triggerId, UUID leaseToken, Instant renewedAt, Instant leaseExpiresAt) {
        return execute("Failed to renew automatic-investigation trigger lease", handle ->
                handle.createUpdate(RENEW_AUTOMATIC_INVESTIGATION_TRIGGER)
                        .bind("triggerId", triggerId)
                        .bind("leaseToken", leaseToken)
                        .bind("renewedAt", Timestamp.from(renewedAt))
                        .bind("leaseExpiresAt", Timestamp.from(leaseExpiresAt))
                        .execute() == 1);
    }

    @Override
    public void completeAutomaticInvestigationTrigger(
            UUID triggerId, UUID leaseToken, Instant completedAt, UUID assessmentId) {
        executeExactLeaseUpdate(
                "complete automatic-investigation trigger",
                handle -> handle.createUpdate(COMPLETE_AUTOMATIC_INVESTIGATION_TRIGGER)
                        .bind("triggerId", triggerId)
                        .bind("leaseToken", leaseToken)
                        .bind("completedAt", Timestamp.from(completedAt))
                        .bind("assessmentId", assessmentId)
                        .execute());
    }

    @Override
    public void retryAutomaticInvestigationTrigger(
            UUID triggerId, UUID leaseToken, Instant failedAt, Instant nextAttemptAt, String lastError) {
        executeExactLeaseUpdate(
                "schedule automatic-investigation trigger retry",
                handle -> handle.createUpdate(RETRY_AUTOMATIC_INVESTIGATION_TRIGGER)
                        .bind("triggerId", triggerId)
                        .bind("leaseToken", leaseToken)
                        .bind("failedAt", Timestamp.from(failedAt))
                        .bind("nextAttemptAt", Timestamp.from(nextAttemptAt))
                        .bind("lastError", lastError)
                        .execute());
    }

    @Override
    public void exhaustAutomaticInvestigationTrigger(
            UUID triggerId, UUID leaseToken, Instant failedAt, String lastError) {
        executeExactLeaseUpdate(
                "exhaust automatic-investigation trigger",
                handle -> handle.createUpdate(EXHAUST_AUTOMATIC_INVESTIGATION_TRIGGER)
                        .bind("triggerId", triggerId)
                        .bind("leaseToken", leaseToken)
                        .bind("failedAt", Timestamp.from(failedAt))
                        .bind("lastError", lastError)
                        .execute());
    }

    private HumanAttentionAlert mapHumanAttentionAlert(ResultSet rows) throws SQLException {
        Timestamp resolvedAt = rows.getTimestamp("resolved_at");
        Object modelAttentionSuggested = rows.getObject("model_attention_suggested");
        return new HumanAttentionAlert(
                rows.getObject("alert_id", UUID.class),
                HumanAttentionAlertState.valueOf(rows.getString("state")),
                HumanAttentionAlertSeverity.valueOf(rows.getString("severity")),
                HumanAttentionAlertReason.valueOf(rows.getString("reason")),
                rows.getString("policy_version"),
                rows.getTimestamp("opened_at").toInstant(),
                rows.getTimestamp("last_observed_at").toInstant(),
                resolvedAt == null ? null : resolvedAt.toInstant(),
                rows.getObject("first_snapshot_id", UUID.class),
                rows.getObject("latest_snapshot_id", UUID.class),
                HealthStatus.valueOf(rows.getString("latest_health_status")),
                rows.getInt("latest_health_score"),
                rows.getObject("latest_assessment_id", UUID.class),
                modelAttentionSuggested == null ? null : rows.getBoolean("model_attention_suggested"));
    }

    private AutomaticInvestigationTrigger mapAutomaticInvestigationTrigger(ResultSet rows) throws SQLException {
        return new AutomaticInvestigationTrigger(
                rows.getObject("trigger_id", UUID.class),
                rows.getObject("snapshot_id", UUID.class),
                AutomaticInvestigationTriggerType.valueOf(rows.getString("trigger_type")),
                AutomaticInvestigationTriggerState.valueOf(rows.getString("state")),
                rows.getTimestamp("created_at").toInstant(),
                rows.getTimestamp("next_attempt_at").toInstant(),
                rows.getInt("attempt_count"),
                rows.getTimestamp("completed_at") == null ? null : rows.getTimestamp("completed_at").toInstant(),
                rows.getObject("assessment_id", UUID.class),
                rows.getString("last_error"));
    }

    private OperationalChangeRecord mapChange(ResultSet rows) throws SQLException {
        return new OperationalChangeRecord(
                rows.getObject("change_id", UUID.class),
                rows.getTimestamp("changed_at").toInstant(),
                OperationalChangeCategory.valueOf(rows.getString("category")),
                OperationalChangeTargetType.valueOf(rows.getString("target_type")),
                rows.getString("target_id"),
                readJson(rows.getString("before_state"), STRING_MAP),
                readJson(rows.getString("after_state"), STRING_MAP),
                OperationalChangeOutcome.valueOf(rows.getString("outcome")),
                OperationalChangeSource.valueOf(rows.getString("change_source")),
                rows.getString("actor_id"),
                rows.getString("correlation_id"),
                rows.getString("trace_id"),
                rows.getString("application_version"));
    }


    private IncidentAssessment mapIncidentAssessment(ResultSet rows) throws SQLException {
        return new IncidentAssessment(
                rows.getObject("assessment_id", UUID.class),
                rows.getObject("snapshot_id", UUID.class),
                rows.getTimestamp("created_at").toInstant(),
                IncidentAssessmentSource.valueOf(rows.getString("source")),
                rows.getString("provider"),
                rows.getString("model"),
                rows.getString("summary"),
                readJson(rows.getString("suspected_subsystems"), STRING_LIST),
                rows.getDouble("confidence"),
                readJson(rows.getString("observations"), STRING_LIST),
                readJson(rows.getString("hypotheses"), STRING_LIST),
                readJson(rows.getString("evidence_references"), STRING_LIST),
                readJson(rows.getString("recommended_checks"), STRING_LIST),
                rows.getBoolean("human_attention_suggested"));
    }

    private HealthSnapshot mapSnapshot(ResultSet rows) throws SQLException {
        return new HealthSnapshot(
                rows.getObject("snapshot_id", UUID.class),
                rows.getTimestamp("generated_at").toInstant(),
                rows.getTimestamp("window_started_at").toInstant(),
                rows.getTimestamp("window_ended_at").toInstant(),
                HealthStatus.valueOf(rows.getString("overall_status")),
                rows.getInt("health_score"),
                rows.getString("policy_version"),
                readJson(rows.getString("component_statuses"), STRING_MAP),
                readJson(rows.getString("signal_values"), DOUBLE_MAP),
                readJson(rows.getString("anomaly_candidates"), STRING_LIST),
                readJson(rows.getString("anomaly_details"), HEALTH_ANOMALY_LIST),
                readJson(rows.getString("recent_change_ids"), UUID_LIST),
                rows.getString("application_version"),
                rows.getBoolean("evidence_complete"),
                readJson(rows.getString("unknown_reasons"), STRING_LIST));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new OperationalIntelligencePersistenceException("Failed to serialize operational JSON", exception);
        }
    }

    private <T> T readJson(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new OperationalIntelligencePersistenceException("Failed to deserialize operational JSON", exception);
        }
    }

    private void executeExactAlertUpdate(String action, HandleIntFunction operation) {
        int updated = execute("Failed to " + action, operation::apply);
        if (updated != 1) {
            throw new OperationalIntelligencePersistenceException(
                    "Cannot " + action + " because the active alert state changed concurrently");
        }
    }

    private void executeExactLeaseUpdate(String action, HandleIntFunction operation) {
        int updated = execute("Failed to " + action, operation::apply);
        if (updated != 1) {
            throw new OperationalIntelligencePersistenceException(
                    "Cannot " + action + " because the exact live lease is no longer owned");
        }
    }

    private <T> T execute(String message, HandleFunction<T> operation) {
        try {
            return jdbi.withHandle(handle -> {
                requireActiveTransaction(handle);
                return operation.apply(handle);
            });
        } catch (OperationalIntelligencePersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new OperationalIntelligencePersistenceException(message, exception);
        }
    }

    private void executeVoid(String message, HandleConsumer operation) {
        try {
            jdbi.useHandle(handle -> {
                requireActiveTransaction(handle);
                operation.accept(handle);
            });
        } catch (OperationalIntelligencePersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new OperationalIntelligencePersistenceException(message, exception);
        }
    }

    private static void requireActiveTransaction(Handle handle) {
        if (!handle.isInTransaction()) {
            throw new IllegalStateException("Operations persistence requires an application-owned transaction");
        }
    }

    @FunctionalInterface
    private interface HandleIntFunction {
        int apply(Handle handle);
    }

    @FunctionalInterface
    private interface HandleFunction<T> {
        T apply(Handle handle);
    }

    @FunctionalInterface
    private interface HandleConsumer {
        void accept(Handle handle);
    }
}
