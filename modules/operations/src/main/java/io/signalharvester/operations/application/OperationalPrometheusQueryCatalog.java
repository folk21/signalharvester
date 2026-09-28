package io.signalharvester.operations.application;

import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Stable allowlisted Prometheus expressions shared by Health Engine collection and assisted investigation. */
@Singleton
public final class OperationalPrometheusQueryCatalog {

    public Map<String, String> queries(Duration window) {
        long seconds = Math.max(1L, window.toSeconds());
        String range = "[" + seconds + "s]";
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put(
                DeterministicStatisticalHealthEngine.BACKEND_UNAVAILABLE_REPLICAS,
                "clamp_min(sum(kube_deployment_spec_replicas{namespace=\"signalharvester\",deployment=\"signalharvester-backend\"})"
                        + " - sum(kube_deployment_status_replicas_available{namespace=\"signalharvester\",deployment=\"signalharvester-backend\"}), 0)");
        queries.put(
                DeterministicStatisticalHealthEngine.KAFKA_CONSUMER_LAG,
                "sum(redpanda_kafka_consumer_group_lag_sum{job=\"redpanda\"})");
        queries.put(
                DeterministicStatisticalHealthEngine.HTTP_ERROR_RATIO,
                "(sum(rate(http_server_requests_seconds_count{job=\"signalharvester-backend\",status=~\"5..\"}"
                        + range + ")) / clamp_min(sum(rate(http_server_requests_seconds_count{job=\"signalharvester-backend\"}"
                        + range + ")), 0.000001)) or vector(0)");
        queries.put(
                DeterministicStatisticalHealthEngine.HTTP_AVERAGE_LATENCY_SECONDS,
                "(sum(rate(http_server_requests_seconds_sum{job=\"signalharvester-backend\"}" + range
                        + ")) / clamp_min(sum(rate(http_server_requests_seconds_count{job=\"signalharvester-backend\"}"
                        + range + ")), 0.000001)) or vector(0)");
        queries.put(
                DeterministicStatisticalHealthEngine.COLLECTION_SOURCE_FAILURE_RATIO,
                "(sum(rate(signalharvester_collection_source_fetches_total{job=\"signalharvester-backend\",outcome=\"failure\"}"
                        + range + ")) / clamp_min(sum(rate(signalharvester_collection_source_fetches_total{job=\"signalharvester-backend\"}"
                        + range + ")), 0.000001)) or vector(0)");
        queries.put(
                DeterministicStatisticalHealthEngine.ANALYSIS_FAILURE_RATIO,
                "(sum(rate(signalharvester_analysis_items_total{job=\"signalharvester-backend\",status=\"FAILED_EXCEPTION\"}"
                        + range + ")) / clamp_min(sum(rate(signalharvester_analysis_items_total{job=\"signalharvester-backend\"}"
                        + range + ")), 0.000001)) or vector(0)");
        queries.put(
                DeterministicStatisticalHealthEngine.POSTGRES_AVERAGE_LATENCY_SECONDS,
                "(sum(rate(traces_spanmetrics_latency_sum{db_system=\"postgresql\"}" + range
                        + ")) / clamp_min(sum(rate(traces_spanmetrics_latency_count{db_system=\"postgresql\"}" + range
                        + ")), 0.000001)) or vector(0)");
        queries.put(
                DeterministicStatisticalHealthEngine.OUTBOX_PENDING,
                "max(signalharvester_analysis_outbox_pending{job=\"signalharvester-backend\"})");
        queries.put(
                DeterministicStatisticalHealthEngine.OUTBOX_OLDEST_PENDING_AGE_SECONDS,
                "max(signalharvester_analysis_outbox_oldest_pending_age_seconds{job=\"signalharvester-backend\"})");
        queries.put(
                DeterministicStatisticalHealthEngine.OUTBOX_PUBLICATION_FAILURE_RATIO,
                "(sum(rate(signalharvester_analysis_outbox_publications_total{job=\"signalharvester-backend\",outcome=\"failed\"}"
                        + range + ")) / clamp_min(sum(rate(signalharvester_analysis_outbox_publications_total{job=\"signalharvester-backend\"}"
                        + range + ")), 0.000001)) or vector(0)");
        return Map.copyOf(queries);
    }

    public Optional<String> query(String queryId, Duration window) {
        return Optional.ofNullable(queries(window).get(queryId));
    }
}
