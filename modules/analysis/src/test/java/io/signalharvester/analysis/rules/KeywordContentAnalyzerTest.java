package io.signalharvester.analysis.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.signalharvester.analysis.application.AnalysisDecision;
import io.signalharvester.analysis.model.KeywordAnalysisSettings;
import io.signalharvester.analysis.model.NormalizedContentItem;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Verifies deterministic classification, score calculation, tag selection, and captured settings behavior in
 * {@link KeywordContentAnalyzer}.
 *
 * <p>Related feature: {@code ANALYSIS.CLASSIFICATION}.</p>
 */
class KeywordContentAnalyzerTest {

    /** Classify and score matches from the settings supplied for one item. */
    @Test
    void shouldClassifyAndScoreCapturedKeywordMatches() {
        KeywordContentAnalyzer analyzer = new KeywordContentAnalyzer();
        KeywordAnalysisSettings settings = new KeywordAnalysisSettings(
                List.of("java", "kafka", "postgresql"), 2);

        AnalysisDecision decision = analyzer.analyze(
                item("Senior Java role", "Kafka and distributed systems"), settings);

        assertTrue(decision.relevant());
        assertEquals(KeywordContentAnalyzer.MATCHED_CLASSIFICATION, decision.classification());
        assertEquals(67, decision.score());
        assertEquals(List.of("java", "kafka"), decision.tags());
        assertEquals("keyword-v1", decision.analyzer());
    }

    /** Reject impossible minimum-match settings before analysis executes. */
    @Test
    void shouldRejectImpossibleMinimumMatchSettings() {
        assertThrows(IllegalArgumentException.class, () ->
                new KeywordAnalysisSettings(List.of("java", "kafka", "java"), 3));
    }

    /** Explain deterministic non-match using the supplied settings. */
    @Test
    void shouldExplainDeterministicNonMatch() {
        KeywordContentAnalyzer analyzer = new KeywordContentAnalyzer();
        KeywordAnalysisSettings settings = new KeywordAnalysisSettings(List.of("java", "kafka"), 1);

        AnalysisDecision decision = analyzer.analyze(
                item("Frontend role", "React and TypeScript"), settings);

        assertFalse(decision.relevant());
        assertEquals(KeywordContentAnalyzer.UNMATCHED_CLASSIFICATION, decision.classification());
        assertEquals(0, decision.score());
        assertEquals(List.of(), decision.tags());
        assertEquals("No configured keywords matched", decision.explanation());
    }

    private static NormalizedContentItem item(String title, String content) {
        return new NormalizedContentItem(
                "raw-event-01",
                "run-01",
                Optional.empty(),
                Instant.parse("2026-09-10T18:00:00Z"),
                "raw-01",
                "normalized-01",
                "source-01",
                "profile-01",
                "JOB",
                Optional.empty(),
                Optional.of(title),
                URI.create("https://example.test/jobs/1"),
                content,
                "text/plain",
                Map.of(),
                Optional.empty());
    }
}
