package io.signalharvester.configuration.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies intrinsic normalization and threshold invariants for profile-owned Analysis settings. */
class MonitoringProfileAnalysisSettingsTest {

    /** Normalize case and whitespace while preserving first-occurrence order. */
    @Test
    void shouldCanonicalizeKeywords() {
        MonitoringProfileAnalysisSettings settings = new MonitoringProfileAnalysisSettings(
                List.of(" Java ", "KAFKA", "java"), 2);

        assertEquals(List.of("java", "kafka"), settings.keywords());
        assertEquals(2, settings.minimumMatches());
    }

    /** Reject empty keywords and thresholds above the unique keyword count. */
    @Test
    void shouldRejectInvalidSettings() {
        assertThrows(IllegalArgumentException.class, () -> new MonitoringProfileAnalysisSettings(List.of(), 1));
        assertThrows(IllegalArgumentException.class, () ->
                new MonitoringProfileAnalysisSettings(List.of("java", "JAVA"), 2));
    }
}
