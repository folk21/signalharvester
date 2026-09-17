package io.signalharvester.collection.event;

import io.signalharvester.configuration.api.MonitoringProfileAnalysisSettings;
import java.util.Objects;
import java.util.Optional;

/**
 * Supplies flow metadata required to publish one fetched source payload as a raw-item event.
 *
 * <p>The Kafka transport does not own collection-run or monitoring-profile orchestration. The collection
 * run caller provides those identifiers and effective Analysis settings explicitly so processing semantics
 * stay immutable after publication.</p>
 *
 * @param rawItemId caller-owned stable identity for the discovered raw item
 * @param correlationId identifier shared by events in the same logical processing flow
 * @param monitoringProfileId monitoring profile that initiated collection
 * @param informationCategory product-level information category such as JOB or TOPIC
 * @param analysisSettings effective profile-owned deterministic Analysis settings
 * @param traceparent W3C traceparent value when a tracing boundary supplies one
 */
public record RawItemPublicationContext(
        String rawItemId,
        String correlationId,
        String monitoringProfileId,
        String informationCategory,
        MonitoringProfileAnalysisSettings analysisSettings,
        Optional<String> traceparent) {

    public RawItemPublicationContext {
        requireNonBlank(rawItemId, "rawItemId");
        requireNonBlank(correlationId, "correlationId");
        requireNonBlank(monitoringProfileId, "monitoringProfileId");
        requireNonBlank(informationCategory, "informationCategory");
        Objects.requireNonNull(analysisSettings, "analysisSettings");
        Objects.requireNonNull(traceparent, "traceparent");
        traceparent.ifPresent(value -> requireNonBlank(value, "traceparent"));
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
