package io.signalharvester.configuration.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies intrinsic normalization and threshold invariants for profile-owned Analysis settings.
 *
 * <p>Features: {@code CONFIGURATION.MONITORING_PROFILES}, {@code ANALYSIS.CLASSIFICATION}.</p>
 */
class MonitoringProfileAnalysisSettingsTest {

    /** Normalize case and whitespace while preserving first-occurrence order. */
    @Test
    void shouldCanonicalizeKeywords() {
        MonitoringProfileAnalysisSettings settings = new MonitoringProfileAnalysisSettings(
                List.of(" Java ", "KAFKA", "java"), 2);

        assertEquals(List.of("java", "kafka"), settings.keywords());
        assertEquals(2, settings.minimumMatches());
    }

    /** Represent all-relevant analysis with no keywords and a zero threshold. */
    @Test
    void shouldRepresentAllRelevantAnalysis() {
        MonitoringProfileAnalysisSettings settings = MonitoringProfileAnalysisSettings.allRelevant();

        assertEquals(List.of(), settings.keywords());
        assertEquals(0, settings.minimumMatches());
        assertTrue(settings.isAllRelevant());
    }

    /** Reject mixed empty/threshold states and thresholds above the unique keyword count. */
    @Test
    void shouldRejectInvalidSettings() {
        assertThrows(IllegalArgumentException.class, () -> new MonitoringProfileAnalysisSettings(List.of(), 1));
        assertThrows(IllegalArgumentException.class, () -> new MonitoringProfileAnalysisSettings(List.of("java"), 0));
        assertThrows(IllegalArgumentException.class, () ->
                new MonitoringProfileAnalysisSettings(List.of("java", "JAVA"), 2));
    }
}
