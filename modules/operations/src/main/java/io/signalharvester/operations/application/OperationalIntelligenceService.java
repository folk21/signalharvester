package io.signalharvester.operations.application;

import io.micronaut.context.annotation.Value;
import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.operations.api.OperationalChangeRecord;
import io.signalharvester.operations.assisted.AssistedInvestigationConfiguration;
import io.signalharvester.operations.assisted.HealthAnalysisPackageFactory;
import io.signalharvester.operations.model.HealthAnalysisPackage;
import io.signalharvester.operations.model.HealthAnomaly;
import io.signalharvester.operations.model.HealthSnapshot;
import io.signalharvester.operations.persistence.OperationalIntelligenceRepository;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Owns operational timeline reads plus versioned deterministic/statistical Health Snapshot evaluation. */
@Singleton
public final class OperationalIntelligenceService implements OperationalIntelligenceOperations {
    private static final int MAX_RECENT_CHANGES = 20;
    private static final int MAX_REPORT_CHANGE_LOOKUP = 100;

    private final OperationalIntelligenceRepository repository;
    private final TransactionOperations<Connection> transactions;
    private final HealthReportRenderer reportRenderer;
    private final HealthAnalysisPackageFactory analysisPackageFactory;
    private final AssistedInvestigationConfiguration assistedConfiguration;
    private final OperationalHealthSignalCollector signalCollector;
    private final HealthEngine healthEngine;
    private final HealthPolicyConfiguration healthConfiguration;
    private final Clock clock;
    private final String applicationVersion;
    private final int snapshotRetentionCount;

    public OperationalIntelligenceService(
            OperationalIntelligenceRepository repository,
            @Named("default") TransactionOperations<Connection> transactions,
            HealthReportRenderer reportRenderer,
            HealthAnalysisPackageFactory analysisPackageFactory,
            AssistedInvestigationConfiguration assistedConfiguration,
            OperationalHealthSignalCollector signalCollector,
            HealthEngine healthEngine,
            HealthPolicyConfiguration healthConfiguration,
            @Value("${signalharvester.build.version:dev}") String applicationVersion,
            @Value("${signalharvester.operations.health-snapshot-retention-count:1000}") int snapshotRetentionCount) {
        this(
                repository,
                transactions,
                reportRenderer,
                analysisPackageFactory,
                assistedConfiguration,
                signalCollector,
                healthEngine,
                healthConfiguration,
                Clock.systemUTC(),
                applicationVersion,
                snapshotRetentionCount);
    }

    OperationalIntelligenceService(
            OperationalIntelligenceRepository repository,
            TransactionOperations<Connection> transactions,
            HealthReportRenderer reportRenderer,
            HealthAnalysisPackageFactory analysisPackageFactory,
            AssistedInvestigationConfiguration assistedConfiguration,
            OperationalHealthSignalCollector signalCollector,
            HealthEngine healthEngine,
            HealthPolicyConfiguration healthConfiguration,
            Clock clock,
            String applicationVersion,
            int snapshotRetentionCount) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.reportRenderer = Objects.requireNonNull(reportRenderer, "reportRenderer");
        this.analysisPackageFactory = Objects.requireNonNull(analysisPackageFactory, "analysisPackageFactory");
        this.assistedConfiguration = Objects.requireNonNull(assistedConfiguration, "assistedConfiguration");
        this.signalCollector = Objects.requireNonNull(signalCollector, "signalCollector");
        this.healthEngine = Objects.requireNonNull(healthEngine, "healthEngine");
        this.healthConfiguration = Objects.requireNonNull(healthConfiguration, "healthConfiguration");
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
    public HealthSnapshot captureHealthSnapshot() {
        HealthSignalEvidence evidence = signalCollector.collect();
        Instant generatedAt = clock.instant();
        return transactions.executeWrite(status -> captureInCurrentTransaction(generatedAt, evidence));
    }

    Optional<HealthSnapshot> captureScheduledSnapshot() {
        HealthSignalEvidence evidence = signalCollector.collect();
        Instant generatedAt = clock.instant();
        return transactions.executeWrite(status -> {
            if (!repository.tryAcquireHealthSamplingLock()) {
                return Optional.empty();
            }
            Optional<HealthSnapshot> latest = repository.findLatestSnapshot();
            if (latest.isPresent()
                    && latest.get().generatedAt().plus(healthConfiguration.getSamplingInterval()).isAfter(generatedAt)) {
                return Optional.empty();
            }
            return Optional.of(captureInCurrentTransaction(generatedAt, evidence));
        });
    }

    @Override
    public HealthSnapshot latestSnapshot() {
        return transactions.executeRead(status -> repository.findLatestSnapshot()
                .orElseThrow(HealthSnapshotNotFoundException::new));
    }

    @Override
    public String latestMarkdownReport() {
        return renderReport(latestSnapshot());
    }

    @Override
    public HealthAnalysisPackage latestAnalysisPackage() {
        HealthSnapshot snapshot = latestSnapshot();
        return analysisPackageFactory.create(
                snapshot,
                renderReport(snapshot),
                assistedConfiguration.getMaxReportChars());
    }

    @Override
    public HealthAnalysisPackage analysisPackage(UUID snapshotId) {
        Objects.requireNonNull(snapshotId, "snapshotId");
        HealthSnapshot snapshot = transactions.executeRead(status -> repository.findSnapshot(snapshotId)
                .orElseThrow(HealthSnapshotNotFoundException::new));
        return analysisPackageFactory.create(
                snapshot,
                renderReport(snapshot),
                assistedConfiguration.getMaxReportChars());
    }

    @Override
    public OperationalHealthCorrelation correlateChange(UUID changeId) {
        return transactions.executeRead(status -> {
            OperationalChangeRecord change = repository.findChange(changeId)
                    .orElseThrow(() -> new OperationalChangeNotFoundException(changeId));
            HealthSnapshot before = repository.findLatestSnapshotAtOrBefore(change.changedAt()).orElse(null);
            HealthSnapshot after = repository.findEarliestSnapshotAtOrAfter(change.changedAt()).orElse(null);
            return OperationalHealthCorrelation.between(change, before, after);
        });
    }


    private String renderReport(HealthSnapshot snapshot) {
        Set<UUID> referenced = Set.copyOf(snapshot.recentChangeIds());
        record ReportEvidence(List<OperationalChangeRecord> changes, HealthSnapshot previous) {}
        ReportEvidence reportEvidence = transactions.executeRead(status -> {
            List<OperationalChangeRecord> changes = repository.findChangesBetween(
                            snapshot.windowStartedAt(), snapshot.windowEndedAt(), MAX_REPORT_CHANGE_LOOKUP)
                    .stream()
                    .filter(change -> referenced.contains(change.id()))
                    .toList();
            HealthSnapshot previous = repository.findRecentSnapshotsBefore(snapshot.generatedAt(), 1).stream()
                    .findFirst()
                    .orElse(null);
            return new ReportEvidence(changes, previous);
        });
        return reportRenderer.render(snapshot, reportEvidence.previous(), reportEvidence.changes());
    }

    private HealthSnapshot captureInCurrentTransaction(Instant generatedAt, HealthSignalEvidence evidence) {
        Instant windowStartedAt = generatedAt.minus(healthConfiguration.getWindow());
        List<OperationalChangeRecord> changes = repository.findChangesBetween(
                windowStartedAt, generatedAt, MAX_RECENT_CHANGES);
        List<HealthSnapshot> baseline = repository.findRecentSnapshotsBefore(
                generatedAt, healthConfiguration.getBaselineMaxSamples());
        HealthEvaluation evaluation = healthEngine.evaluate(evidence, baseline);
        List<String> anomalyCandidates = evaluation.anomalies().stream()
                .map(OperationalIntelligenceService::candidateSummary)
                .toList();
        HealthSnapshot snapshot = new HealthSnapshot(
                UUID.randomUUID(),
                generatedAt,
                windowStartedAt,
                generatedAt,
                evaluation.overallStatus(),
                evaluation.healthScore(),
                healthConfiguration.getPolicyVersion(),
                evaluation.componentStatuses(),
                evaluation.signalValues(),
                anomalyCandidates,
                evaluation.anomalies(),
                changes.stream().map(OperationalChangeRecord::id).toList(),
                applicationVersion,
                evaluation.evidenceComplete(),
                evaluation.unknownReasons());
        repository.insertSnapshot(snapshot);
        repository.deleteSnapshotsBeyond(snapshotRetentionCount);
        return snapshot;
    }

    private static String candidateSummary(HealthAnomaly anomaly) {
        return anomaly.signal() + ":" + anomaly.severity().name() + ":" + anomaly.detector();
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
