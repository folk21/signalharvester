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
    private static final String INSERT_INCIDENT_ASSESSMENT = SqlResources.load(SQL_PATH, "insert-incident-assessment");
    private static final String FIND_RECENT_INCIDENT_ASSESSMENTS = SqlResources.load(SQL_PATH, "find-recent-incident-assessments");
    private static final String DELETE_OLD_INCIDENT_ASSESSMENTS = SqlResources.load(SQL_PATH, "delete-old-incident-assessments");

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
    private interface HandleFunction<T> {
        T apply(Handle handle);
    }

    @FunctionalInterface
    private interface HandleConsumer {
        void accept(Handle handle);
    }
}
