package io.signalharvester.operations.assisted;

import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.operations.application.OperationalIntelligenceOperations;
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
    private final Clock clock;

    public AssistedInvestigationService(
            OperationalIntelligenceOperations operations,
            OperationalIntelligenceRepository repository,
            @Named("default") TransactionOperations<Connection> transactions,
            List<IncidentAnalyst> analysts,
            AssistedInvestigationConfiguration configuration,
            InvestigationToolbox toolbox) {
        this(operations, repository, transactions, analysts, configuration, toolbox, Clock.systemUTC());
    }

    AssistedInvestigationService(
            OperationalIntelligenceOperations operations,
            OperationalIntelligenceRepository repository,
            TransactionOperations<Connection> transactions,
            List<IncidentAnalyst> analysts,
            AssistedInvestigationConfiguration configuration,
            InvestigationToolbox toolbox,
            Clock clock) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.analysts = List.copyOf(Objects.requireNonNull(analysts, "analysts"));
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.toolbox = Objects.requireNonNull(toolbox, "toolbox");
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
        IncidentAnalyst analyst = configuredAnalyst();
        var toolSession = toolbox.openSession(analysisPackage.snapshotId());
        IncidentInvestigationResult investigation;
        try {
            investigation = analyst.investigate(analysisPackage, toolSession);
        } catch (InvestigationBudgetExceededException exception) {
            throw new IncidentAnalystException("LLM investigation exceeded an application-owned budget", exception);
        }
        try {
            validateEvidenceReferences(
                    investigation.assessment(),
                    analysisPackage,
                    toolSession.discoveredEvidenceReferences());
        } catch (InvalidIncidentAssessmentException exception) {
            throw new IncidentAnalystException("LLM provider returned an assessment with invalid evidence references", exception);
        }
        return persist(
                analysisPackage.snapshotId(),
                IncidentAssessmentSource.PROVIDER,
                analyst.providerId(),
                analyst.modelId(),
                investigation.assessment());
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
        IncidentAssessment assessment = new IncidentAssessment(
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
        return transactions.executeWrite(status -> {
            if (repository.findSnapshot(snapshotId).isEmpty()) {
                throw new InvalidIncidentAssessmentException("Health Snapshot does not exist: " + snapshotId);
            }
            repository.insertIncidentAssessment(assessment);
            repository.deleteIncidentAssessmentsBeyond(configuration.getAssessmentRetentionCount());
            return assessment;
        });
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
