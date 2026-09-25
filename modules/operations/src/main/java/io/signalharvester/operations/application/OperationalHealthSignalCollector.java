package io.signalharvester.operations.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Collects bounded health signals from local Micrometer state and optional cluster-wide Prometheus queries. */
@Singleton
public final class OperationalHealthSignalCollector {
    private final HealthPolicyConfiguration configuration;
    private final Optional<MeterRegistry> meterRegistry;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OperationalPrometheusQueryCatalog queryCatalog;

    public OperationalHealthSignalCollector(
            HealthPolicyConfiguration configuration,
            Optional<MeterRegistry> meterRegistry,
            OperationalPrometheusQueryCatalog queryCatalog) {
        this(
                configuration,
                meterRegistry,
                HttpClient.newBuilder().connectTimeout(configuration.getPrometheusQueryTimeout()).build(),
                new ObjectMapper(),
                queryCatalog);
    }

    OperationalHealthSignalCollector(
            HealthPolicyConfiguration configuration,
            Optional<MeterRegistry> meterRegistry,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            OperationalPrometheusQueryCatalog queryCatalog) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.queryCatalog = Objects.requireNonNull(queryCatalog, "queryCatalog");
    }

    HealthSignalEvidence collect() {
        Map<String, Double> values = new LinkedHashMap<>();
        List<String> unknownReasons = new ArrayList<>();
        captureLocalOutbox(values);

        String baseUrl = configuration.getPrometheusBaseUrl().trim();
        if (baseUrl.isEmpty()) {
            unknownReasons.add("prometheus-evidence-disabled");
            return new HealthSignalEvidence(values, unknownReasons);
        }

        Map<String, String> queries = queryCatalog.queries(configuration.getWindow());
        Map<String, CompletableFuture<HttpResponse<String>>> pending = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : queries.entrySet()) {
            try {
                HttpRequest request = HttpRequest.newBuilder(queryUri(baseUrl, entry.getValue()))
                        .timeout(configuration.getPrometheusQueryTimeout())
                        .GET()
                        .build();
                pending.put(entry.getKey(), httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
            } catch (RuntimeException failure) {
                unknownReasons.add("prometheus-query-failed:" + entry.getKey());
            }
        }

        for (Map.Entry<String, CompletableFuture<HttpResponse<String>>> entry : pending.entrySet()) {
            try {
                HttpResponse<String> response = entry.getValue().join();
                Optional<Double> value = parseScalar(response);
                if (value.isPresent()) {
                    values.put(entry.getKey(), value.get());
                } else {
                    unknownReasons.add("prometheus-signal-unavailable:" + entry.getKey());
                }
            } catch (RuntimeException failure) {
                unknownReasons.add("prometheus-query-failed:" + entry.getKey());
            }
        }
        return new HealthSignalEvidence(values, unknownReasons);
    }

    private void captureLocalOutbox(Map<String, Double> values) {
        if (meterRegistry.isEmpty()) {
            return;
        }
        captureGauge(
                values,
                DeterministicStatisticalHealthEngine.OUTBOX_PENDING,
                "signalharvester.analysis.outbox.pending");
        captureGauge(
                values,
                DeterministicStatisticalHealthEngine.OUTBOX_OLDEST_PENDING_AGE_SECONDS,
                "signalharvester.analysis.outbox.oldest.pending.age");
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

    private URI queryUri(String baseUrl, String query) {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalized + "/api/v1/query?query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8));
    }

    private Optional<Double> parseScalar(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            if (!"success".equals(root.path("status").asText())) {
                return Optional.empty();
            }
            JsonNode result = root.path("data").path("result");
            if (!result.isArray() || result.isEmpty()) {
                return Optional.empty();
            }
            JsonNode value = result.get(0).path("value");
            if (!value.isArray() || value.size() < 2) {
                return Optional.empty();
            }
            double parsed = Double.parseDouble(value.get(1).asText());
            return Double.isFinite(parsed) ? Optional.of(parsed) : Optional.empty();
        } catch (IOException | NumberFormatException failure) {
            return Optional.empty();
        }
    }
}
