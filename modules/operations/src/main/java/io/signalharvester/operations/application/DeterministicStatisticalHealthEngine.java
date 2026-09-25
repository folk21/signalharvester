package io.signalharvester.operations.application;

import io.signalharvester.operations.model.HealthAnomaly;
import io.signalharvester.operations.model.HealthSnapshot;
import io.signalharvester.operations.model.HealthStatus;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Initial explainable Health Engine using configurable hard rules plus rolling median/MAD deviation. */
@Singleton
public final class DeterministicStatisticalHealthEngine implements HealthEngine {
    static final String BACKEND_UNAVAILABLE_REPLICAS = "kubernetes.backendUnavailableReplicas";
    static final String KAFKA_CONSUMER_LAG = "kafka.consumerLag";
    static final String HTTP_ERROR_RATIO = "http.errorRatio";
    static final String HTTP_AVERAGE_LATENCY_SECONDS = "http.averageLatencySeconds";
    static final String COLLECTION_SOURCE_FAILURE_RATIO = "collection.sourceFailureRatio";
    static final String ANALYSIS_FAILURE_RATIO = "analysis.failureRatio";
    static final String POSTGRES_AVERAGE_LATENCY_SECONDS = "postgresql.averageLatencySeconds";
    static final String OUTBOX_PENDING = "analysis.outbox.pending";
    static final String OUTBOX_OLDEST_PENDING_AGE_SECONDS = "analysis.outbox.oldestPendingAgeSeconds";
    static final String OUTBOX_PUBLICATION_FAILURE_RATIO = "analysis.outbox.publicationFailureRatio";

    private static final double MAD_SCALE = 0.6744897501960817;
    private static final double EPSILON = 1.0e-9;

    private final HealthPolicyConfiguration configuration;
    private final List<SignalRule> rules;
    private final Set<String> requiredSignals;

    public DeterministicStatisticalHealthEngine(HealthPolicyConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        validateConfiguration(configuration);
        this.rules = List.of(
                new SignalRule(
                        BACKEND_UNAVAILABLE_REPLICAS,
                        "backend-runtime",
                        configuration.getBackendUnavailableReplicasDegraded(),
                        configuration.getBackendUnavailableReplicasUnhealthy(),
                        true),
                new SignalRule(
                        KAFKA_CONSUMER_LAG,
                        "eventing",
                        configuration.getKafkaLagDegraded(),
                        configuration.getKafkaLagUnhealthy(),
                        true),
                new SignalRule(
                        HTTP_ERROR_RATIO,
                        "backend-runtime",
                        configuration.getHttpErrorRatioDegraded(),
                        configuration.getHttpErrorRatioUnhealthy(),
                        false),
                new SignalRule(
                        HTTP_AVERAGE_LATENCY_SECONDS,
                        "backend-runtime",
                        seconds(configuration.getHttpLatencyDegraded()),
                        seconds(configuration.getHttpLatencyUnhealthy()),
                        false),
                new SignalRule(
                        COLLECTION_SOURCE_FAILURE_RATIO,
                        "collection",
                        configuration.getFailureRatioDegraded(),
                        configuration.getFailureRatioUnhealthy(),
                        false),
                new SignalRule(
                        ANALYSIS_FAILURE_RATIO,
                        "analysis",
                        configuration.getFailureRatioDegraded(),
                        configuration.getFailureRatioUnhealthy(),
                        false),
                new SignalRule(
                        POSTGRES_AVERAGE_LATENCY_SECONDS,
                        "postgresql",
                        seconds(configuration.getPostgresLatencyDegraded()),
                        seconds(configuration.getPostgresLatencyUnhealthy()),
                        false),
                new SignalRule(
                        OUTBOX_PENDING,
                        "analysis-outbox",
                        configuration.getOutboxPendingDegraded(),
                        configuration.getOutboxPendingUnhealthy(),
                        true),
                new SignalRule(
                        OUTBOX_OLDEST_PENDING_AGE_SECONDS,
                        "analysis-outbox",
                        seconds(configuration.getOutboxOldestPendingDegraded()),
                        seconds(configuration.getOutboxOldestPendingUnhealthy()),
                        true),
                new SignalRule(
                        OUTBOX_PUBLICATION_FAILURE_RATIO,
                        "analysis-outbox",
                        configuration.getFailureRatioDegraded(),
                        configuration.getFailureRatioUnhealthy(),
                        false));
        this.requiredSignals = rules.stream()
                .filter(SignalRule::required)
                .map(SignalRule::signal)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public HealthEvaluation evaluate(HealthSignalEvidence evidence, List<HealthSnapshot> baselineSnapshots) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(baselineSnapshots, "baselineSnapshots");

        Map<String, Double> current = sanitizeSignals(evidence.values());
        Map<String, HealthAnomaly> strongestBySignal = new LinkedHashMap<>();
        Map<String, HealthStatus> componentStatuses = new LinkedHashMap<>();
        Map<String, Integer> componentEvidenceCount = new HashMap<>();
        LinkedHashSet<String> unknownReasons = new LinkedHashSet<>(evidence.unknownReasons());

        for (SignalRule rule : rules) {
            Double observed = current.get(rule.signal());
            if (observed == null) {
                continue;
            }
            componentEvidenceCount.merge(rule.component(), 1, Integer::sum);
            strongestBySignal.compute(rule.signal(), (ignored, existing) -> strongest(
                    existing, hardThresholdAnomaly(rule, observed)));
            strongestBySignal.compute(rule.signal(), (ignored, existing) -> strongest(
                    existing, statisticalAnomaly(rule, observed, baselineSnapshots)));
        }

        for (SignalRule rule : rules) {
            if (componentEvidenceCount.containsKey(rule.component())) {
                componentStatuses.putIfAbsent(rule.component(), HealthStatus.HEALTHY);
            }
        }
        strongestBySignal.values().stream()
                .filter(Objects::nonNull)
                .forEach(anomaly -> {
                    String component = ruleFor(anomaly.signal()).component();
                    componentStatuses.merge(component, anomaly.severity(), DeterministicStatisticalHealthEngine::worse);
                });

        List<String> missingRequired = requiredSignals.stream()
                .filter(signal -> !current.containsKey(signal))
                .sorted()
                .toList();
        missingRequired.forEach(signal -> unknownReasons.add("required-signal-unavailable:" + signal));

        int baselineSamples = Math.min(baselineSnapshots.size(), configuration.getBaselineMaxSamples());
        boolean baselineReady = baselineSamples >= configuration.getBaselineMinSamples();
        if (!baselineReady) {
            unknownReasons.add("rolling-baseline-insufficient:" + baselineSamples + "/"
                    + configuration.getBaselineMinSamples());
        }

        List<HealthAnomaly> anomalies = strongestBySignal.values().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(HealthAnomaly::severity).reversed()
                        .thenComparing(HealthAnomaly::signal))
                .toList();
        HealthStatus strongest = anomalies.stream()
                .map(HealthAnomaly::severity)
                .reduce(HealthStatus.HEALTHY, DeterministicStatisticalHealthEngine::worse);

        HealthStatus overall;
        if (strongest == HealthStatus.UNHEALTHY || strongest == HealthStatus.DEGRADED) {
            overall = strongest;
        } else if (!missingRequired.isEmpty()) {
            overall = HealthStatus.UNKNOWN;
        } else {
            overall = HealthStatus.HEALTHY;
        }

        int score = overall == HealthStatus.UNKNOWN ? 0 : score(anomalies);
        boolean evidenceComplete = missingRequired.isEmpty() && baselineReady && evidence.unknownReasons().isEmpty();
        Map<String, String> componentStatusNames = componentStatuses.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().name()));
        return new HealthEvaluation(
                overall,
                score,
                componentStatusNames,
                current,
                anomalies,
                evidenceComplete,
                List.copyOf(unknownReasons));
    }

    private HealthAnomaly hardThresholdAnomaly(SignalRule rule, double observed) {
        if (observed < rule.degradedThreshold()) {
            return null;
        }
        HealthStatus severity = observed >= rule.unhealthyThreshold()
                ? HealthStatus.UNHEALTHY
                : HealthStatus.DEGRADED;
        double reference = severity == HealthStatus.UNHEALTHY
                ? rule.unhealthyThreshold()
                : rule.degradedThreshold();
        double anomalyScore = normalizedHardScore(observed, rule.degradedThreshold(), rule.unhealthyThreshold());
        return new HealthAnomaly(
                rule.signal(),
                "hard-threshold",
                severity,
                observed,
                reference,
                anomalyScore,
                "observed value crossed configured " + severity.name().toLowerCase() + " threshold");
    }

    private HealthAnomaly statisticalAnomaly(
            SignalRule rule,
            double observed,
            List<HealthSnapshot> baselineSnapshots) {
        List<Double> baseline = baselineSnapshots.stream()
                .limit(configuration.getBaselineMaxSamples())
                .map(snapshot -> snapshot.signalValues().get(rule.signal()))
                .filter(Objects::nonNull)
                .filter(Double::isFinite)
                .sorted()
                .toList();
        if (baseline.size() < configuration.getBaselineMinSamples()) {
            return null;
        }
        double median = median(baseline);
        List<Double> deviations = baseline.stream()
                .map(value -> Math.abs(value - median))
                .sorted()
                .toList();
        double mad = median(deviations);
        if (mad <= EPSILON || observed <= median) {
            return null;
        }
        double robustZ = MAD_SCALE * (observed - median) / mad;
        if (robustZ < configuration.getDegradedRobustZScore()) {
            return null;
        }
        HealthStatus severity = robustZ >= configuration.getUnhealthyRobustZScore()
                ? HealthStatus.UNHEALTHY
                : HealthStatus.DEGRADED;
        double anomalyScore = Math.min(1.0, robustZ / configuration.getUnhealthyRobustZScore());
        return new HealthAnomaly(
                rule.signal(),
                "rolling-median-mad",
                severity,
                observed,
                median,
                anomalyScore,
                "observed value is " + rounded(robustZ) + " robust standard deviations above rolling median");
    }

    private int score(List<HealthAnomaly> anomalies) {
        int score = 100;
        for (HealthAnomaly anomaly : anomalies) {
            score -= anomaly.severity() == HealthStatus.UNHEALTHY
                    ? configuration.getUnhealthyPenalty()
                    : configuration.getDegradedPenalty();
        }
        return Math.max(0, score);
    }

    private SignalRule ruleFor(String signal) {
        return rules.stream()
                .filter(rule -> rule.signal().equals(signal))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing Health Engine rule for signal " + signal));
    }

    private static HealthAnomaly strongest(HealthAnomaly first, HealthAnomaly second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        int severity = Integer.compare(rank(first.severity()), rank(second.severity()));
        if (severity != 0) {
            return severity >= 0 ? first : second;
        }
        return first.anomalyScore() >= second.anomalyScore() ? first : second;
    }

    private static HealthStatus worse(HealthStatus first, HealthStatus second) {
        return rank(first) >= rank(second) ? first : second;
    }

    private static int rank(HealthStatus status) {
        return switch (status) {
            case HEALTHY -> 0;
            case UNKNOWN -> 1;
            case DEGRADED -> 2;
            case UNHEALTHY -> 3;
        };
    }

    private static Map<String, Double> sanitizeSignals(Map<String, Double> values) {
        Map<String, Double> result = new LinkedHashMap<>();
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(entry -> entry.getValue() != null && Double.isFinite(entry.getValue()))
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(result);
    }

    private static double median(List<Double> sorted) {
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("median requires at least one value");
        }
        int middle = sorted.size() / 2;
        if ((sorted.size() & 1) == 1) {
            return sorted.get(middle);
        }
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }

    private static double normalizedHardScore(double observed, double degraded, double unhealthy) {
        if (observed >= unhealthy || unhealthy <= degraded) {
            return 1.0;
        }
        double progress = (observed - degraded) / (unhealthy - degraded);
        return Math.max(0.5, Math.min(1.0, 0.5 + progress * 0.5));
    }

    private static double seconds(java.time.Duration value) {
        return value.toNanos() / 1_000_000_000.0;
    }

    private static String rounded(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static void validateConfiguration(HealthPolicyConfiguration configuration) {
        requireIncreasing(
                configuration.getDegradedRobustZScore(),
                configuration.getUnhealthyRobustZScore(),
                "robust z-score thresholds");
        requireIncreasing(
                configuration.getBackendUnavailableReplicasDegraded(),
                configuration.getBackendUnavailableReplicasUnhealthy(),
                "backend unavailable-replica thresholds");
        requireIncreasing(
                configuration.getHttpLatencyDegraded().toNanos(),
                configuration.getHttpLatencyUnhealthy().toNanos(),
                "HTTP latency thresholds");
        requireIncreasing(
                configuration.getOutboxPendingDegraded(),
                configuration.getOutboxPendingUnhealthy(),
                "outbox pending thresholds");
        requireIncreasing(
                seconds(configuration.getOutboxOldestPendingDegraded()),
                seconds(configuration.getOutboxOldestPendingUnhealthy()),
                "outbox age thresholds");
        requireIncreasing(
                configuration.getKafkaLagDegraded(),
                configuration.getKafkaLagUnhealthy(),
                "Kafka lag thresholds");
        requireIncreasing(
                configuration.getHttpErrorRatioDegraded(),
                configuration.getHttpErrorRatioUnhealthy(),
                "HTTP error-ratio thresholds");
        requireIncreasing(
                seconds(configuration.getPostgresLatencyDegraded()),
                seconds(configuration.getPostgresLatencyUnhealthy()),
                "PostgreSQL latency thresholds");
        requireIncreasing(
                configuration.getFailureRatioDegraded(),
                configuration.getFailureRatioUnhealthy(),
                "failure-ratio thresholds");
        if (configuration.getBaselineMaxSamples() < configuration.getBaselineMinSamples()) {
            throw new IllegalArgumentException("baselineMaxSamples must be >= baselineMinSamples");
        }
    }

    private static void requireIncreasing(double degraded, double unhealthy, String name) {
        if (!Double.isFinite(degraded) || !Double.isFinite(unhealthy) || degraded < 0.0 || unhealthy <= degraded) {
            throw new IllegalArgumentException(name + " must be finite and unhealthy must be greater than degraded");
        }
    }

    private record SignalRule(
            String signal,
            String component,
            double degradedThreshold,
            double unhealthyThreshold,
            boolean required) {
    }
}
