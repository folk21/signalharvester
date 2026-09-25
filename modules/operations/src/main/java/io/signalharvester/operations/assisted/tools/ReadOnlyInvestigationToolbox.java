package io.signalharvester.operations.assisted.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.operations.api.OperationalChangeCategory;
import io.signalharvester.operations.api.OperationalChangeRecord;
import io.signalharvester.operations.application.HealthPolicyConfiguration;
import io.signalharvester.operations.application.HealthSnapshotNotFoundException;
import io.signalharvester.operations.application.OperationalPrometheusQueryCatalog;
import io.signalharvester.operations.assisted.AssistedInvestigationConfiguration;
import io.signalharvester.operations.model.HealthAnomaly;
import io.signalharvester.operations.model.HealthSnapshot;
import io.signalharvester.operations.persistence.OperationalIntelligenceRepository;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/** Application-owned allowlisted read-only telemetry/history toolbox for one bounded LLM investigation. */
@Singleton
public final class ReadOnlyInvestigationToolbox implements InvestigationToolbox {
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-fA-F]{16,32}");
    private static final int MAX_CHANGE_LOOKUP = 200;

    private final OperationalIntelligenceRepository repository;
    private final TransactionOperations<Connection> transactions;
    private final HealthPolicyConfiguration healthConfiguration;
    private final AssistedInvestigationConfiguration configuration;
    private final OperationalPrometheusQueryCatalog prometheusCatalog;
    private final TelemetrySanitizer sanitizer;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ReadOnlyInvestigationToolbox(
            OperationalIntelligenceRepository repository,
            @Named("default") TransactionOperations<Connection> transactions,
            HealthPolicyConfiguration healthConfiguration,
            AssistedInvestigationConfiguration configuration,
            OperationalPrometheusQueryCatalog prometheusCatalog,
            TelemetrySanitizer sanitizer) {
        this(
                repository,
                transactions,
                healthConfiguration,
                configuration,
                prometheusCatalog,
                sanitizer,
                HttpClient.newBuilder().connectTimeout(configuration.getRequestTimeout()).build(),
                new ObjectMapper());
    }

    ReadOnlyInvestigationToolbox(
            OperationalIntelligenceRepository repository,
            TransactionOperations<Connection> transactions,
            HealthPolicyConfiguration healthConfiguration,
            AssistedInvestigationConfiguration configuration,
            OperationalPrometheusQueryCatalog prometheusCatalog,
            TelemetrySanitizer sanitizer,
            HttpClient httpClient,
            ObjectMapper objectMapper) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.healthConfiguration = Objects.requireNonNull(healthConfiguration, "healthConfiguration");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.prometheusCatalog = Objects.requireNonNull(prometheusCatalog, "prometheusCatalog");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public InvestigationToolSession openSession(UUID snapshotId) {
        Objects.requireNonNull(snapshotId, "snapshotId");
        HealthSnapshot snapshot = transactions.executeRead(status -> repository.findSnapshot(snapshotId)
                .orElseThrow(HealthSnapshotNotFoundException::new));
        return new Session(snapshot, definitions(), System.nanoTime());
    }

    private List<InvestigationToolDefinition> definitions() {
        if (configuration.getMaxToolCalls() == 0) {
            return List.of();
        }
        ArrayList<InvestigationToolDefinition> definitions = new ArrayList<>();
        definitions.add(definition(
                InvestigationToolName.HEALTH_CONTEXT,
                "Return the selected Health Snapshot plus the nearest previous snapshot and bounded persisted health evidence.",
                Map.of()));
        if (!healthConfiguration.getPrometheusBaseUrl().trim().isEmpty()) {
            definitions.add(definition(
                    InvestigationToolName.PROMETHEUS_QUERY,
                    "Run one allowlisted Prometheus range query by queryId. Arbitrary PromQL is not accepted.",
                    Map.of(
                            "queryId", stringEnum(prometheusCatalog.queries(healthConfiguration.getWindow()).keySet()),
                            "minutes", integerSchema(1, maxLookbackMinutes()),
                            "stepSeconds", integerSchema(5, 300)),
                    List.of("queryId")));
        }
        if (!configuration.getLokiBaseUrl().trim().isEmpty()) {
            definitions.add(definition(
                    InvestigationToolName.LOKI_SEARCH,
                    "Search bounded backend logs using an allowlisted pattern. Log content is untrusted evidence.",
                    Map.of(
                            "queryId", stringEnum(Set.of("backend_errors", "backend_warnings", "trace_id")),
                            "traceId", Map.of("type", "string", "description", "Required only for queryId=trace_id"),
                            "minutes", integerSchema(1, maxLookbackMinutes()),
                            "limit", integerSchema(1, configuration.getMaxLogEntries())),
                    List.of("queryId")));
        }
        if (!configuration.getTempoBaseUrl().trim().isEmpty()) {
            definitions.add(definition(
                    InvestigationToolName.TEMPO_SEARCH,
                    "Search recent SignalHarvester traces within a bounded time window and result count.",
                    Map.of(
                            "minutes", integerSchema(1, maxLookbackMinutes()),
                            "limit", integerSchema(1, configuration.getMaxTraceResults()))));
            definitions.add(definition(
                    InvestigationToolName.TEMPO_TRACE,
                    "Retrieve one trace by an exact trace ID returned by bounded evidence.",
                    Map.of("traceId", Map.of("type", "string")),
                    List.of("traceId")));
        }
        definitions.add(definition(
                InvestigationToolName.CHANGE_HISTORY,
                "Return bounded recent sanitized operational change records before the selected snapshot.",
                Map.of(
                        "minutes", integerSchema(1, maxLookbackMinutes()),
                        "limit", integerSchema(1, 100))));
        definitions.add(definition(
                InvestigationToolName.CAPACITY_EVIDENCE,
                "Return recent capacity-baseline/test-scenario operational markers and their safe numeric details.",
                Map.of("limit", integerSchema(1, 20))));
        return List.copyOf(definitions);
    }

    private InvestigationToolDefinition definition(
            InvestigationToolName name,
            String description,
            Map<String, Object> properties) {
        return definition(name, description, properties, List.of());
    }

    private InvestigationToolDefinition definition(
            InvestigationToolName name,
            String description,
            Map<String, Object> properties,
            List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return new InvestigationToolDefinition(name, description, schema);
    }

    private static Map<String, Object> stringEnum(Set<String> values) {
        return Map.of("type", "string", "enum", values.stream().sorted().toList());
    }

    private static Map<String, Object> integerSchema(int minimum, int maximum) {
        return Map.of("type", "integer", "minimum", minimum, "maximum", maximum);
    }

    private int maxLookbackMinutes() {
        return Math.max(1, (int) Math.min(Integer.MAX_VALUE, configuration.getMaxTelemetryLookback().toMinutes()));
    }

    private final class Session implements InvestigationToolSession {
        private final HealthSnapshot snapshot;
        private final Map<InvestigationToolName, InvestigationToolDefinition> available;
        private final long startedNanos;
        private final AtomicInteger calls = new AtomicInteger();
        private final LinkedHashSet<String> evidenceReferences = new LinkedHashSet<>();

        private Session(HealthSnapshot snapshot, List<InvestigationToolDefinition> definitions, long startedNanos) {
            this.snapshot = snapshot;
            this.available = definitions.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                    InvestigationToolDefinition::name,
                    definition -> definition));
            this.startedNanos = startedNanos;
        }

        @Override
        public List<InvestigationToolDefinition> definitions() {
            return available.values().stream()
                    .sorted(Comparator.comparing(definition -> definition.name().externalName()))
                    .toList();
        }

        @Override
        public InvestigationToolResult execute(InvestigationToolRequest request) {
            Objects.requireNonNull(request, "request");
            if (!available.containsKey(request.tool())) {
                throw new InvestigationBudgetExceededException("Investigation tool is not enabled: " + request.tool().externalName());
            }
            ensureWithinDuration();
            int callNumber = calls.incrementAndGet();
            if (callNumber > configuration.getMaxToolCalls()) {
                throw new InvestigationBudgetExceededException(
                        "Investigation exceeded maximum tool calls: " + configuration.getMaxToolCalls());
            }
            InvestigationToolResult result;
            try {
                result = switch (request.tool()) {
                    case HEALTH_CONTEXT -> healthContext(request);
                    case PROMETHEUS_QUERY -> prometheusQuery(request);
                    case LOKI_SEARCH -> lokiSearch(request);
                    case TEMPO_SEARCH -> tempoSearch(request);
                    case TEMPO_TRACE -> tempoTrace(request);
                    case CHANGE_HISTORY -> changeHistory(request);
                    case CAPACITY_EVIDENCE -> capacityEvidence(request);
                };
            } catch (IllegalArgumentException failure) {
                result = result(request, false, "tool input rejected: " + failure.getMessage(), List.of());
            } catch (RuntimeException failure) {
                result = result(request, false, "tool unavailable: " + failure.getClass().getSimpleName(), List.of());
            }
            evidenceReferences.addAll(result.evidenceReferences());
            ensureWithinDuration();
            return result;
        }

        @Override
        public List<String> discoveredEvidenceReferences() {
            return List.copyOf(evidenceReferences);
        }

        @Override
        public int toolCallCount() {
            return calls.get();
        }

        private InvestigationToolResult healthContext(InvestigationToolRequest request) {
            requireNoUnexpectedArguments(request, Set.of());
            HealthSnapshot previous = transactions.executeRead(status -> repository
                    .findRecentSnapshotsBefore(snapshot.generatedAt(), 1)
                    .stream()
                    .findFirst()
                    .orElse(null));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("selected", snapshotSummary(snapshot));
            payload.put("previous", previous == null ? null : snapshotSummary(previous));
            ArrayList<String> refs = new ArrayList<>(snapshotReferences(snapshot));
            if (previous != null) {
                refs.addAll(snapshotReferences(previous));
            }
            return result(request, true, json(payload), refs);
        }

        private InvestigationToolResult prometheusQuery(InvestigationToolRequest request) {
            requireOnlyArguments(request, Set.of("queryId", "minutes", "stepSeconds"));
            String queryId = required(request, "queryId");
            int minutes = boundedInt(request, "minutes", Math.min(10, maxLookbackMinutes()), 1, maxLookbackMinutes());
            int stepSeconds = boundedInt(request, "stepSeconds", 30, 5, 300);
            Duration lookback = Duration.ofMinutes(minutes);
            String expression = prometheusCatalog.query(queryId, lookback)
                    .orElseThrow(() -> new IllegalArgumentException("unsupported Prometheus queryId: " + queryId));
            Instant end = snapshot.windowEndedAt();
            Instant start = end.minus(lookback);
            String base = normalizeBaseUrl(healthConfiguration.getPrometheusBaseUrl());
            URI uri = URI.create(base + "/api/v1/query_range?query=" + encode(expression)
                    + "&start=" + start.getEpochSecond()
                    + "&end=" + end.getEpochSecond()
                    + "&step=" + stepSeconds);
            String body = httpGet(uri);
            String reference = "prometheus:" + queryId + ":" + start.getEpochSecond() + ":" + end.getEpochSecond();
            return result(request, true, body, List.of(reference));
        }

        private InvestigationToolResult lokiSearch(InvestigationToolRequest request) {
            requireOnlyArguments(request, Set.of("queryId", "traceId", "minutes", "limit"));
            String queryId = required(request, "queryId");
            int minutes = boundedInt(request, "minutes", Math.min(10, maxLookbackMinutes()), 1, maxLookbackMinutes());
            int limit = boundedInt(request, "limit", Math.min(20, configuration.getMaxLogEntries()), 1, configuration.getMaxLogEntries());
            String selector = "{namespace=\"signalharvester\",app=\"signalharvester-backend\"}";
            String query = switch (queryId) {
                case "backend_errors" -> selector + " |~ `(?i)error|exception|failed`";
                case "backend_warnings" -> selector + " |~ `(?i)warn|timeout|retry`";
                case "trace_id" -> {
                    String traceId = required(request, "traceId").toLowerCase();
                    validateTraceId(traceId);
                    requireDiscoveredTrace(traceId);
                    yield selector + " |= `" + traceId + "`";
                }
                default -> throw new IllegalArgumentException("unsupported Loki queryId: " + queryId);
            };
            Instant end = snapshot.windowEndedAt();
            Instant start = end.minus(Duration.ofMinutes(minutes));
            String base = normalizeBaseUrl(configuration.getLokiBaseUrl());
            URI uri = URI.create(base + "/loki/api/v1/query_range?query=" + encode(query)
                    + "&start=" + toEpochNanos(start)
                    + "&end=" + toEpochNanos(end)
                    + "&limit=" + limit
                    + "&direction=backward");
            String body = httpGet(uri);
            ArrayList<String> refs = new ArrayList<>();
            refs.add("loki:" + queryId + ":" + start.getEpochSecond() + ":" + end.getEpochSecond());
            if ("trace_id".equals(queryId)) {
                refs.add("trace:" + request.arguments().get("traceId"));
            }
            return result(request, true, body, refs);
        }

        private InvestigationToolResult tempoSearch(InvestigationToolRequest request) {
            requireOnlyArguments(request, Set.of("minutes", "limit"));
            int minutes = boundedInt(request, "minutes", Math.min(10, maxLookbackMinutes()), 1, maxLookbackMinutes());
            int limit = boundedInt(request, "limit", Math.min(10, configuration.getMaxTraceResults()), 1, configuration.getMaxTraceResults());
            Instant end = snapshot.windowEndedAt();
            Instant start = end.minus(Duration.ofMinutes(minutes));
            String base = normalizeBaseUrl(configuration.getTempoBaseUrl());
            URI uri = URI.create(base + "/api/search?tags=" + encode("service.name=signalharvester")
                    + "&start=" + start.getEpochSecond()
                    + "&end=" + end.getEpochSecond()
                    + "&limit=" + limit);
            String body = httpGet(uri);
            List<String> refs = extractTraceReferences(body, limit);
            return result(request, true, body, refs);
        }

        private InvestigationToolResult tempoTrace(InvestigationToolRequest request) {
            requireOnlyArguments(request, Set.of("traceId"));
            String traceId = required(request, "traceId").toLowerCase();
            validateTraceId(traceId);
            requireDiscoveredTrace(traceId);
            String base = normalizeBaseUrl(configuration.getTempoBaseUrl());
            String body = httpGet(URI.create(base + "/api/traces/" + traceId));
            return result(request, true, body, List.of("trace:" + traceId));
        }

        private void requireDiscoveredTrace(String traceId) {
            if (!evidenceReferences.contains("trace:" + traceId)) {
                throw new IllegalArgumentException(
                        "traceId must first be returned by bounded investigation evidence: " + traceId);
            }
        }

        private InvestigationToolResult changeHistory(InvestigationToolRequest request) {
            requireOnlyArguments(request, Set.of("minutes", "limit"));
            int minutes = boundedInt(request, "minutes", Math.min(30, maxLookbackMinutes()), 1, maxLookbackMinutes());
            int limit = boundedInt(request, "limit", 20, 1, 100);
            Instant from = snapshot.generatedAt().minus(Duration.ofMinutes(minutes));
            List<OperationalChangeRecord> changes = transactions.executeRead(status -> repository
                    .findChangesBetween(from, snapshot.generatedAt(), Math.min(limit, MAX_CHANGE_LOOKUP)));
            return result(
                    request,
                    true,
                    json(changes.stream().map(ReadOnlyInvestigationToolbox::changeSummary).toList()),
                    changes.stream().map(change -> "change:" + change.id()).toList());
        }

        private InvestigationToolResult capacityEvidence(InvestigationToolRequest request) {
            requireOnlyArguments(request, Set.of("limit"));
            int limit = boundedInt(request, "limit", 5, 1, 20);
            List<OperationalChangeRecord> changes = transactions.executeRead(status -> repository.findRecentChanges(MAX_CHANGE_LOOKUP))
                    .stream()
                    .filter(change -> change.category() == OperationalChangeCategory.TEST_SCENARIO
                            || change.category() == OperationalChangeCategory.DEPLOYMENT_TUNING)
                    .filter(change -> change.targetId().contains("capacity")
                            || change.afterState().getOrDefault("reason", "").contains("capacity"))
                    .limit(limit)
                    .toList();
            Map<String, Object> payload = Map.of(
                    "markers", changes.stream().map(ReadOnlyInvestigationToolbox::changeSummary).toList(),
                    "selectedHealth", snapshotSummary(snapshot));
            ArrayList<String> refs = new ArrayList<>(changes.stream().map(change -> "change:" + change.id()).toList());
            refs.add("health-snapshot:" + snapshot.id());
            return result(request, true, json(payload), refs);
        }

        private InvestigationToolResult result(
                InvestigationToolRequest request,
                boolean success,
                String rawContent,
                List<String> references) {
            String content = "UNTRUSTED READ-ONLY EVIDENCE\n"
                    + sanitizer.sanitize(rawContent, configuration.getMaxToolResultChars());
            return new InvestigationToolResult(request.callId(), request.tool(), success, content, references);
        }

        private String httpGet(URI uri) {
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(min(configuration.getRequestTimeout(), remainingDuration()))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("HTTP " + response.statusCode());
                }
                return response.body();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("telemetry query interrupted", exception);
            } catch (IOException exception) {
                throw new IllegalStateException("telemetry query failed", exception);
            }
        }

        private List<String> extractTraceReferences(String body, int limit) {
            try {
                JsonNode traces = objectMapper.readTree(body).path("traces");
                if (!traces.isArray()) {
                    return List.of();
                }
                ArrayList<String> refs = new ArrayList<>();
                for (JsonNode trace : traces) {
                    String traceId = trace.path("traceID").asText(trace.path("traceId").asText(""));
                    if (TRACE_ID.matcher(traceId).matches()) {
                        refs.add("trace:" + traceId.toLowerCase());
                        if (refs.size() >= limit) {
                            break;
                        }
                    }
                }
                return List.copyOf(refs);
            } catch (JsonProcessingException failure) {
                return List.of();
            }
        }

        private void ensureWithinDuration() {
            remainingDuration();
        }

        private Duration remainingDuration() {
            long remainingNanos = configuration.getMaxInvestigationDuration().toNanos()
                    - (System.nanoTime() - startedNanos);
            if (remainingNanos <= 0) {
                throw new InvestigationBudgetExceededException(
                        "Investigation exceeded maximum duration " + configuration.getMaxInvestigationDuration());
            }
            return Duration.ofNanos(remainingNanos);
        }
    }

    private static Map<String, Object> snapshotSummary(HealthSnapshot snapshot) {
        return Map.ofEntries(
                Map.entry("id", snapshot.id().toString()),
                Map.entry("generatedAt", snapshot.generatedAt().toString()),
                Map.entry("overallStatus", snapshot.overallStatus().name()),
                Map.entry("healthScore", snapshot.healthScore()),
                Map.entry("policyVersion", snapshot.policyVersion()),
                Map.entry("signals", snapshot.signalValues()),
                Map.entry("anomalies", snapshot.anomalyDetails().stream().map(ReadOnlyInvestigationToolbox::anomalySummary).toList()),
                Map.entry("recentChangeIds", snapshot.recentChangeIds().stream().map(UUID::toString).toList()),
                Map.entry("evidenceComplete", snapshot.evidenceComplete()),
                Map.entry("unknownReasons", snapshot.unknownReasons()));
    }

    private static Map<String, Object> anomalySummary(HealthAnomaly anomaly) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("signal", anomaly.signal());
        summary.put("detector", anomaly.detector());
        summary.put("severity", anomaly.severity().name());
        summary.put("observedValue", anomaly.observedValue());
        summary.put("referenceValue", anomaly.referenceValue());
        summary.put("anomalyScore", anomaly.anomalyScore());
        summary.put("evidence", anomaly.evidence());
        return summary;
    }

    private static Map<String, Object> changeSummary(OperationalChangeRecord change) {
        return Map.ofEntries(
                Map.entry("id", change.id().toString()),
                Map.entry("changedAt", change.changedAt().toString()),
                Map.entry("category", change.category().name()),
                Map.entry("targetType", change.targetType().name()),
                Map.entry("targetId", change.targetId()),
                Map.entry("beforeState", change.beforeState()),
                Map.entry("afterState", change.afterState()),
                Map.entry("outcome", change.outcome().name()),
                Map.entry("source", change.source().name()),
                Map.entry("actorId", change.actorId()),
                Map.entry("correlationId", change.correlationId()),
                Map.entry("traceId", change.traceId()),
                Map.entry("applicationVersion", change.applicationVersion()));
    }

    private static List<String> snapshotReferences(HealthSnapshot snapshot) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        refs.add("health-snapshot:" + snapshot.id());
        snapshot.signalValues().keySet().stream().sorted().map(signal -> "signal:" + signal).forEach(refs::add);
        snapshot.anomalyDetails().stream().map(anomaly -> "anomaly:" + anomaly.signal()).sorted().forEach(refs::add);
        snapshot.recentChangeIds().stream().map(id -> "change:" + id).forEach(refs::add);
        return List.copyOf(refs);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to encode bounded investigation evidence", exception);
        }
    }

    private static void requireNoUnexpectedArguments(InvestigationToolRequest request, Set<String> allowed) {
        requireOnlyArguments(request, allowed);
    }

    private static void requireOnlyArguments(InvestigationToolRequest request, Set<String> allowed) {
        List<String> unexpected = request.arguments().keySet().stream().filter(key -> !allowed.contains(key)).toList();
        if (!unexpected.isEmpty()) {
            throw new IllegalArgumentException("unexpected arguments: " + unexpected);
        }
    }

    private static String required(InvestigationToolRequest request, String name) {
        String value = request.arguments().get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required argument: " + name);
        }
        return value.trim();
    }

    private static int boundedInt(
            InvestigationToolRequest request,
            String name,
            int defaultValue,
            int minimum,
            int maximum) {
        String value = request.arguments().get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
        return parsed;
    }

    private static String normalizeBaseUrl(String value) {
        String trimmed = Objects.requireNonNull(value, "base URL").trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("telemetry endpoint is disabled");
        }
        URI uri = URI.create(trimmed);
        if (!uri.isAbsolute() || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("telemetry endpoint must be an absolute HTTP(S) URI");
        }
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static Duration min(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static long toEpochNanos(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
    }

    private static void validateTraceId(String traceId) {
        if (!TRACE_ID.matcher(traceId).matches()) {
            throw new IllegalArgumentException("traceId must contain 16-32 hexadecimal characters");
        }
    }
}
