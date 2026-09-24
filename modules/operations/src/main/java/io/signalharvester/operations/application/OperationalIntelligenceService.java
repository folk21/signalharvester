package io.signalharvester.operations.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Value;
import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.operations.api.OperationalChangeRecord;
import io.signalharvester.operations.model.HealthSnapshot;
import io.signalharvester.operations.model.HealthStatus;
import io.signalharvester.operations.persistence.OperationalIntelligenceRepository;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Owns bounded operational timeline reads and the pre-detector Health Snapshot/report foundation. */
@Singleton
public final class OperationalIntelligenceService implements OperationalIntelligenceOperations {
    static final String FOUNDATION_POLICY = "foundation-v1";
    private static final int MAX_RECENT_CHANGES = 20;
    private static final int MAX_REPORT_CHANGE_LOOKUP = 100;
    private static final Duration FOUNDATION_WINDOW = Duration.ofMinutes(5);

    private final OperationalIntelligenceRepository repository;
    private final TransactionOperations<Connection> transactions;
    private final HealthReportRenderer reportRenderer;
    private final Optional<MeterRegistry> meterRegistry;
    private final Clock clock;
    private final String applicationVersion;
    private final int snapshotRetentionCount;

    public OperationalIntelligenceService(
            OperationalIntelligenceRepository repository,
            @Named("default") TransactionOperations<Connection> transactions,
            HealthReportRenderer reportRenderer,
            Optional<MeterRegistry> meterRegistry,
            @Value("${signalharvester.build.version:dev}") String applicationVersion,
            @Value("${signalharvester.operations.health-snapshot-retention-count:1000}") int snapshotRetentionCount) {
        this(
                repository,
                transactions,
                reportRenderer,
                meterRegistry,
                Clock.systemUTC(),
                applicationVersion,
                snapshotRetentionCount);
    }

    OperationalIntelligenceService(
            OperationalIntelligenceRepository repository,
            TransactionOperations<Connection> transactions,
            HealthReportRenderer reportRenderer,
            Optional<MeterRegistry> meterRegistry,
            Clock clock,
            String applicationVersion,
            int snapshotRetentionCount) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.reportRenderer = Objects.requireNonNull(reportRenderer, "reportRenderer");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.applicationVersion = requireText(applicationVersion, "applicationVersion");
        if (snapshotRetentionCount < 1 || snapshotRetentionCount > 100_000) {
            throw new IllegalArgumentException("snapshotRetentionCount must be between 1 and 100000");
        }
        this.snapshotRetentionCount = snapshotRetentionCount;
    }

    @Override
    public List<OperationalChangeRecord> recentChanges(int limit) {
        int bounded = Math.max(1, Math.min(limit, 200));
        return transactions.executeRead(status -> repository.findRecentChanges(bounded));
    }

    @Override
    public HealthSnapshot captureFoundationSnapshot() {
        Instant generatedAt = clock.instant();
        List<OperationalChangeRecord> changes = transactions.executeRead(status -> repository.findChangesBetween(
                generatedAt.minus(FOUNDATION_WINDOW), generatedAt, MAX_RECENT_CHANGES));
        Map<String, Double> signals = captureSignals();
        List<String> unknownReasons = new ArrayList<>();
        unknownReasons.add("deterministic-statistical-health-engine-not-active");
        if (signals.isEmpty()) {
            unknownReasons.add("foundation-capacity-signals-unavailable");
        }
        HealthSnapshot snapshot = new HealthSnapshot(
                UUID.randomUUID(),
                generatedAt,
                generatedAt.minus(FOUNDATION_WINDOW),
                generatedAt,
                HealthStatus.UNKNOWN,
                0,
                FOUNDATION_POLICY,
                Map.of("operational-intelligence", HealthStatus.UNKNOWN.name()),
                signals,
                List.of(),
                changes.stream().map(OperationalChangeRecord::id).toList(),
                applicationVersion,
                false,
                unknownReasons);
        return transactions.executeWrite(status -> {
            repository.insertSnapshot(snapshot);
            repository.deleteSnapshotsBeyond(snapshotRetentionCount);
            return snapshot;
        });
    }

    @Override
    public HealthSnapshot latestSnapshot() {
        return transactions.executeRead(status -> repository.findLatestSnapshot()
                .orElseThrow(HealthSnapshotNotFoundException::new));
    }

    @Override
    public String latestMarkdownReport() {
        HealthSnapshot snapshot = latestSnapshot();
        Set<UUID> referenced = Set.copyOf(snapshot.recentChangeIds());
        List<OperationalChangeRecord> changes = transactions.executeRead(status -> repository.findChangesBetween(
                        snapshot.windowStartedAt(), snapshot.windowEndedAt(), MAX_REPORT_CHANGE_LOOKUP))
                .stream()
                .filter(change -> referenced.contains(change.id()))
                .toList();
        return reportRenderer.render(snapshot, changes);
    }

    @Override
    public OperationalHealthCorrelation correlateChange(UUID changeId) {
        return transactions.executeRead(status -> {
            OperationalChangeRecord change = repository.findChange(changeId)
                    .orElseThrow(() -> new OperationalChangeNotFoundException(changeId));
            HealthSnapshot before = repository.findLatestSnapshotAtOrBefore(change.changedAt()).orElse(null);
            HealthSnapshot after = repository.findEarliestSnapshotAtOrAfter(change.changedAt()).orElse(null);
            return new OperationalHealthCorrelation(change, before, after);
        });
    }

    private Map<String, Double> captureSignals() {
        if (meterRegistry.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> values = new LinkedHashMap<>();
        captureGauge(values, "analysis.outbox.pending", "signalharvester.analysis.outbox.pending");
        captureGauge(
                values,
                "analysis.outbox.oldestPendingAgeSeconds",
                "signalharvester.analysis.outbox.oldest.pending.age");
        return Map.copyOf(values);
    }

    private void captureGauge(Map<String, Double> target, String key, String meterName) {
        Gauge gauge = meterRegistry.orElseThrow().find(meterName).gauge();
        if (gauge == null) {
            return;
        }
        double value = gauge.value();
        if (Double.isFinite(value)) {
            target.put(key, value);
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return trimmed;
    }
}
