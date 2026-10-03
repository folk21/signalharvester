package io.signalharvester.operations.assisted;

import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.operations.api.OperationalChangeCategory;
import io.signalharvester.operations.api.OperationalChangeOutcome;
import io.signalharvester.operations.api.OperationalChangeTargetType;
import io.signalharvester.operations.application.OperationalIntelligenceOperations;
import io.signalharvester.operations.alert.AlertDecisionService;
import io.signalharvester.operations.model.HealthAnalysisPackage;
import io.signalharvester.operations.assisted.tools.InvestigationBudgetExceededException;
import io.signalharvester.operations.assisted.tools.InvestigationToolbox;
import io.signalharvester.operations.model.IncidentAssessment;
import io.signalharvester.operations.model.IncidentAssessmentDraft;
import io.signalharvester.operations.model.IncidentAssessmentSource;
import io.signalharvester.operations.persistence.OperationalIntelligenceRepository;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Persists bounded structured assessments while keeping model invocation outside database transactions. */
@Singleton
public final class AssistedInvestigationService implements AssistedInvestigationOperations {
    private final OperationalIntelligenceOperations operations;
    private final OperationalIntelligenceRepository repository;
    private final TransactionOperations<Connection> transactions;
    private final List<IncidentAnalyst> analysts;
    private final AssistedInvestigationConfiguration configuration;
    private final InvestigationToolbox toolbox;
    private final AlertDecisionService alertDecisionService;
    private final Clock clock;

    public AssistedInvestigationService(
            OperationalIntelligenceOperations operations,
            OperationalIntelligenceRepository repository,
            @Named("default") TransactionOperations<Connection> transactions,
            List<IncidentAnalyst> analysts,
            AssistedInvestigationConfiguration configuration,
            InvestigationToolbox toolbox,
            AlertDecisionService alertDecisionService) {
        this(operations, repository, transactions, analysts, configuration, toolbox, alertDecisionService, Clock.systemUTC());
    }

    AssistedInvestigationService(
            OperationalIntelligenceOperations operations,
            OperationalIntelligenceRepository repository,
            TransactionOperations<Connection> transactions,
            List<IncidentAnalyst> analysts,
            AssistedInvestigationConfiguration configuration,
            InvestigationToolbox toolbox,
            AlertDecisionService alertDecisionService,
            Clock clock) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.analysts = List.copyOf(Objects.requireNonNull(analysts, "analysts"));
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.toolbox = Objects.requireNonNull(toolbox, "toolbox");
        this.alertDecisionService = Objects.requireNonNull(alertDecisionService, "alertDecisionService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public HealthAnalysisPackage latestAnalysisPackage() {
        return operations.latestAnalysisPackage();
    }

    @Override
    public IncidentAssessment submitManual(UUID snapshotId, IncidentAssessmentDraft draft) {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(draft, "draft");
        HealthAnalysisPackage analysisPackage = operations.analysisPackage(snapshotId);
        validateEvidenceReferences(draft, analysisPackage);
        return persist(snapshotId, IncidentAssessmentSource.MANUAL, "manual", "manual", draft);
    }

    @Override
    public IncidentAssessment analyzeLatest() {
        HealthAnalysisPackage analysisPackage = operations.latestAnalysisPackage();
        ProviderInvestigation investigation = investigateProvider(analysisPackage, IncidentAssessmentSource.PROVIDER);
        return persist(investigation.assessment());
    }

    @Override
    public AssistedInvestigationTrial captureEvaluationTrial(String scenarioRunId, UUID snapshotId) {
        String normalizedScenarioRunId = requireScenarioRunId(scenarioRunId);
        Objects.requireNonNull(snapshotId, "snapshotId");
        HealthAnalysisPackage analysisPackage = operations.analysisPackage(snapshotId);
        validateScenarioLink(snapshotId, normalizedScenarioRunId);
        ProviderInvestigation investigation = investigateProvider(analysisPackage, IncidentAssessmentSource.PROVIDER);
        validateEvaluationReferenceBounds(
                analysisPackage.allowedEvidenceReferences(), investigation.discoveredEvidenceReferences());
        IncidentAssessment assessment = persist(investigation.assessment());
        return new AssistedInvestigationTrial(
                UUID.randomUUID(),
                normalizedScenarioRunId,
                snapshotId,
                clock.instant(),
                assessment,
                investigation.toolCallCount(),
                investigation.roundCount(),
                investigation.durationMs(),
                analysisPackage.allowedEvidenceReferences(),
                investigation.discoveredEvidenceReferences(),
                configuration.getMaxToolCalls(),
                configuration.getMaxRounds(),
                configuration.getMaxInvestigationDuration().toMillis());
    }

    /** Runs one automatic provider investigation for a fixed snapshot without opening a database transaction. */
    IncidentAssessment investigateAutomatic(UUID snapshotId) {
        Objects.requireNonNull(snapshotId, "snapshotId");
        return investigateProvider(operations.analysisPackage(snapshotId), IncidentAssessmentSource.AUTOMATIC_PROVIDER)
                .assessment();
    }

    /** Persists a prevalidated assessment inside the caller-owned transaction. */
    void persistInCurrentTransaction(IncidentAssessment assessment) {
        Objects.requireNonNull(assessment, "assessment");
        if (repository.findSnapshot(assessment.snapshotId()).isEmpty()) {
            throw new InvalidIncidentAssessmentException(
                    "Health Snapshot does not exist: " + assessment.snapshotId());
        }
        repository.insertIncidentAssessment(assessment);
        alertDecisionService.attachAssessmentInCurrentTransaction(assessment);
        repository.deleteIncidentAssessmentsBeyond(configuration.getAssessmentRetentionCount());
    }

    @Override
    public List<IncidentAssessment> recentAssessments(int limit) {
        int bounded = Math.max(1, Math.min(limit, 200));
        return transactions.executeRead(status -> repository.findRecentIncidentAssessments(bounded));
    }

    private IncidentAnalyst configuredAnalyst() {
        String configuredProvider = configuration.getProvider().trim();
        if (configuredProvider.isEmpty() || "off".equalsIgnoreCase(configuredProvider)) {
            throw new AssistedInvestigationUnavailableException("Provider-assisted investigation is disabled");
        }
        List<IncidentAnalyst> matching = analysts.stream()
                .filter(analyst -> analyst.providerId().equalsIgnoreCase(configuredProvider))
                .toList();
        if (matching.size() != 1) {
            throw new AssistedInvestigationUnavailableException(
                    "Expected exactly one incident analyst for provider " + configuredProvider + ", found " + matching.size());
        }
        return matching.getFirst();
    }

    private IncidentAssessment persist(
            UUID snapshotId,
            IncidentAssessmentSource source,
            String provider,
            String model,
            IncidentAssessmentDraft draft) {
        return persist(buildAssessment(snapshotId, source, provider, model, draft));
    }

    private IncidentAssessment persist(IncidentAssessment assessment) {
        return transactions.executeWrite(status -> {
            persistInCurrentTransaction(assessment);
            return assessment;
        });
    }

    private ProviderInvestigation investigateProvider(
            HealthAnalysisPackage analysisPackage, IncidentAssessmentSource source) {
        IncidentAnalyst analyst = configuredAnalyst();
        var toolSession = toolbox.openSession(analysisPackage.snapshotId());
        long startedNanos = System.nanoTime();
        IncidentInvestigationResult investigation;
        try {
            investigation = analyst.investigate(analysisPackage, toolSession);
        } catch (InvestigationBudgetExceededException exception) {
            throw new IncidentAnalystException("LLM investigation exceeded an application-owned budget", exception);
        }
        long durationMs = Duration.ofNanos(Math.max(0L, System.nanoTime() - startedNanos)).toMillis();
        List<String> discoveredEvidenceReferences = toolSession.discoveredEvidenceReferences();
        try {
            validateEvidenceReferences(
                    investigation.assessment(),
                    analysisPackage,
                    discoveredEvidenceReferences);
        } catch (InvalidIncidentAssessmentException exception) {
            throw new IncidentAnalystException(
                    "LLM provider returned an assessment with invalid evidence references", exception);
        }
        IncidentAssessment assessment = buildAssessment(
                analysisPackage.snapshotId(),
                source,
                analyst.providerId(),
                analyst.modelId(),
                investigation.assessment());
        return new ProviderInvestigation(
                assessment,
                investigation.toolCallCount(),
                investigation.roundCount(),
                durationMs,
                discoveredEvidenceReferences);
    }

    private void validateScenarioLink(UUID snapshotId, String scenarioRunId) {
        boolean linked = transactions.executeRead(status -> repository.findSnapshot(snapshotId)
                .stream()
                .flatMap(snapshot -> snapshot.recentChangeIds().stream())
                .map(repository::findChange)
                .flatMap(java.util.Optional::stream)
                .anyMatch(change -> change.category() == OperationalChangeCategory.TEST_SCENARIO
                        && change.targetType() == OperationalChangeTargetType.SCENARIO
                        && change.outcome() == OperationalChangeOutcome.APPLIED
                        && change.targetId().equals(scenarioRunId)));
        if (!linked) {
            throw new InvalidIncidentAssessmentException(
                    "Health Snapshot is not linked to TEST_SCENARIO run " + scenarioRunId);
        }
    }

    private static void validateEvaluationReferenceBounds(
            List<String> allowedEvidenceReferences, List<String> discoveredEvidenceReferences) {
        if (allowedEvidenceReferences.size() > 64) {
            throw new InvalidIncidentAssessmentException(
                    "Evaluation artifact allowedEvidenceReferences exceeds 64 entries");
        }
        if (discoveredEvidenceReferences.size() > 64) {
            throw new InvalidIncidentAssessmentException(
                    "Evaluation artifact discoveredEvidenceReferences exceeds 64 entries");
        }
    }

    private static String requireScenarioRunId(String value) {
        Objects.requireNonNull(value, "scenarioRunId");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("scenarioRunId must not be blank");
        }
        if (trimmed.length() > 256) {
            throw new IllegalArgumentException("scenarioRunId must be at most 256 characters");
        }
        return trimmed;
    }

    private record ProviderInvestigation(
            IncidentAssessment assessment,
            int toolCallCount,
            int roundCount,
            long durationMs,
            List<String> discoveredEvidenceReferences) {

        private ProviderInvestigation {
            Objects.requireNonNull(assessment, "assessment");
            discoveredEvidenceReferences = List.copyOf(
                    Objects.requireNonNull(discoveredEvidenceReferences, "discoveredEvidenceReferences"));
        }
    }

    private IncidentAssessment buildAssessment(
            UUID snapshotId,
            IncidentAssessmentSource source,
            String provider,
            String model,
            IncidentAssessmentDraft draft) {
        return new IncidentAssessment(
                UUID.randomUUID(),
                snapshotId,
                clock.instant(),
                source,
                provider,
                model,
                draft.summary(),
                draft.suspectedSubsystems(),
                draft.confidence(),
                draft.observations(),
                draft.hypotheses(),
                draft.evidenceReferences(),
                draft.recommendedChecks(),
                draft.humanAttentionSuggested());
    }

    private static void validateEvidenceReferences(
            IncidentAssessmentDraft draft,
            HealthAnalysisPackage analysisPackage) {
        validateEvidenceReferences(draft, analysisPackage, List.of());
    }

    private static void validateEvidenceReferences(
            IncidentAssessmentDraft draft,
            HealthAnalysisPackage analysisPackage,
            List<String> discoveredEvidenceReferences) {
        Set<String> allowed = new HashSet<>(analysisPackage.allowedEvidenceReferences());
        allowed.addAll(discoveredEvidenceReferences);
        List<String> invalid = draft.evidenceReferences().stream().filter(reference -> !allowed.contains(reference)).toList();
        if (!invalid.isEmpty()) {
            throw new InvalidIncidentAssessmentException("Assessment contains unsupported evidence references: " + invalid);
        }
    }
}
