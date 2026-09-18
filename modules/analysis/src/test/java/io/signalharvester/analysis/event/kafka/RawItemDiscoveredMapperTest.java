package io.signalharvester.analysis.event.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.Timestamp;
import io.signalharvester.analysis.configuration.KeywordAnalysisConfiguration;
import io.signalharvester.analysis.model.DiscoveredRawItem;
import io.signalharvester.events.collection.v1.KeywordAnalysisSettings;
import io.signalharvester.events.collection.v1.RawItemDiscovered;
import io.signalharvester.events.common.v1.EventEnvelope;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies raw-event mapping uses captured Analysis settings and preserves legacy-event compatibility.
 *
 * <p>Features: {@code ANALYSIS.CLASSIFICATION}, {@code CONTRACTS.KAFKA_PROTOBUF}.</p>
 */
class RawItemDiscoveredMapperTest {

    /** Prefer the immutable event snapshot over deployment compatibility defaults. */
    @Test
    void shouldUseCapturedAnalysisSettings() {
        RawItemDiscoveredMapper mapper = new RawItemDiscoveredMapper(defaults(List.of("legacy"), 1));
        RawItemDiscovered event = baseEvent().toBuilder()
                .setAnalysisSettings(KeywordAnalysisSettings.newBuilder()
                        .addAllKeywords(List.of("Java", "Kafka"))
                        .setMinimumMatches(2))
                .build();

        DiscoveredRawItem mapped = mapper.map(event);

        assertEquals(List.of("java", "kafka"), mapped.analysisSettings().keywords());
        assertEquals(2, mapped.analysisSettings().minimumMatches());
    }

    /** Use deployment defaults only for legacy events that have no settings snapshot. */
    @Test
    void shouldUseLegacyDefaultsWhenSnapshotIsAbsent() {
        RawItemDiscoveredMapper mapper = new RawItemDiscoveredMapper(defaults(List.of("Legacy", "Fallback"), 1));

        DiscoveredRawItem mapped = mapper.map(baseEvent());

        assertEquals(List.of("legacy", "fallback"), mapped.analysisSettings().keywords());
        assertEquals(1, mapped.analysisSettings().minimumMatches());
    }

    /** Reject deterministic invalid settings at the transport mapping boundary. */
    @Test
    void shouldRejectInvalidCapturedSettings() {
        RawItemDiscoveredMapper mapper = new RawItemDiscoveredMapper(defaults(List.of("legacy"), 1));
        RawItemDiscovered event = baseEvent().toBuilder()
                .setAnalysisSettings(KeywordAnalysisSettings.newBuilder()
                        .addKeywords("java")
                        .setMinimumMatches(2))
                .build();

        assertThrows(IllegalArgumentException.class, () -> mapper.map(event));
    }

    private static RawItemDiscovered baseEvent() {
        return RawItemDiscovered.newBuilder()
                .setEnvelope(EventEnvelope.newBuilder()
                        .setEventId("event-1")
                        .setEventType("collection.raw-item-discovered.v1")
                        .setOccurredAt(Timestamp.newBuilder().setSeconds(1_789_000_000L))
                        .setCorrelationId("run-1")
                        .setProducer("collection")
                        .setSchemaVersion("v1"))
                .setRawItemId("raw-1")
                .setSourceId("source-1")
                .setMonitoringProfileId("profile-1")
                .setInformationCategory("JOB")
                .setUrl("https://example.test/jobs/1")
                .setContent("Java Kafka")
                .setContentType("text/plain")
                .build();
    }

    private static KeywordAnalysisConfiguration defaults(List<String> keywords, int minimumMatches) {
        KeywordAnalysisConfiguration configuration = new KeywordAnalysisConfiguration();
        configuration.setKeywords(keywords);
        configuration.setMinimumMatches(minimumMatches);
        return configuration;
    }
}
