package io.signalharvester.results.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import io.signalharvester.results.application.AnalysisOutcomeProjector;
import io.signalharvester.results.application.ResultDetail;
import io.signalharvester.results.application.ResultLiveBatch;
import io.signalharvester.results.application.ResultLiveCriteria;
import io.signalharvester.results.application.ResultLiveQuery;
import io.signalharvester.results.application.ResultQuery;
import io.signalharvester.results.application.ResultQueryCriteria;
import io.signalharvester.results.application.ResultSummary;
import io.signalharvester.results.model.AnalyzedResult;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies {@link JdbcResultQueryRepository} filtering, deterministic ordering, limits, and detailed child
 * projection loading against the real Results PostgreSQL schema.
 *
 * <p>Related specification: {@code backend-results-rest-api}.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class ResultQueryPostgresIntegrationTest {

    private static final String PROFILE_A = "profile-a";
    private static final String PROFILE_B = "profile-b";
    private static final String SOURCE_A = "source-a";
    private static final String SOURCE_B = "source-b";
    private static final String ITEM_A = "a".repeat(64);
    private static final String ITEM_B = "b".repeat(64);
    private static final String ITEM_C = "c".repeat(64);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("signalharvester")
            .withUsername("signalharvester")
            .withPassword("signalharvester");

    private ApplicationContext context;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        context = ApplicationContext.run(Map.ofEntries(
                Map.entry("datasources.default.url", POSTGRES.getJdbcUrl()),
                Map.entry("datasources.default.username", POSTGRES.getUsername()),
                Map.entry("datasources.default.password", POSTGRES.getPassword()),
                Map.entry("datasources.default.driver-class-name", "org.postgresql.Driver"),
                Map.entry("flyway.datasources.default.enabled", true),
                Map.entry("flyway.datasources.default.locations[0]", "classpath:db/migration/results"),
                Map.entry("kafka.enabled", false),
                Map.entry("signalharvester.results.enabled", false)));
        seedResults();
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    /**
     * Filter recent results by profile, source, category, relevance, classification, and analyzed time range.
     */
    @Test
    void shouldFilterRecentResultsAcrossSupportedDimensions() {
        ResultQuery query = context.getBean(ResultQuery.class);
        ResultQueryCriteria criteria = new ResultQueryCriteria(
                10,
                Optional.of(PROFILE_A),
                Optional.of(SOURCE_A),
                Optional.of("JOB"),
                Optional.of(true),
                Optional.of("MATCHED"),
                Optional.of(Instant.parse("2026-09-13T10:00:00Z")),
                Optional.of(Instant.parse("2026-09-13T10:30:00Z")));

        List<ResultSummary> results = query.recent(criteria);

        assertEquals(1, results.size());
        assertEquals(ITEM_A, results.getFirst().normalizedItemId());
        assertEquals(List.of("java", "kafka"), results.getFirst().tags());
    }

    /**
     * Return newest results first and enforce the requested bounded limit.
     */
    @Test
    void shouldOrderNewestFirstAndApplyLimit() {
        ResultQuery query = context.getBean(ResultQuery.class);
        ResultQueryCriteria criteria = new ResultQueryCriteria(
                2,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        List<ResultSummary> results = query.recent(criteria);

        assertEquals(List.of(ITEM_C, ITEM_B), results.stream().map(ResultSummary::normalizedItemId).toList());
    }

    /**
     * Resume live polling from a durable cursor and do not advance it for redelivery of the same analysis event.
     */
    @Test
    void shouldPollLiveUpdatesAndKeepDuplicateAnalysisEventIdempotent() {
        ResultLiveQuery live = context.getBean(ResultLiveQuery.class);
        AnalysisOutcomeProjector projector = context.getBean(AnalysisOutcomeProjector.class);
        long initialCursor = live.currentCursor();

        AnalyzedResult updated = result(
                "analysis-a-2", "source-event-a-2", "raw-a-2", ITEM_A, SOURCE_A, PROFILE_A, "JOB", true, "MATCHED", 95,
                Instant.parse("2026-09-13T11:10:00Z"), List.of("java", "kafka", "postgresql"),
                Map.of("location", "Remote"), "run-a-2");
        projector.projectAnalyzed(updated);

        ResultLiveBatch batch = live.pollAfter(
                initialCursor,
                new ResultLiveCriteria(
                        Optional.of(PROFILE_A),
                        Optional.of(SOURCE_A),
                        Optional.of("JOB"),
                        Optional.of(true),
                        Optional.of("MATCHED")),
                10);

        assertEquals(1, batch.updates().size());
        assertEquals(ITEM_A, batch.updates().getFirst().result().normalizedItemId());
        assertEquals(95, batch.updates().getFirst().result().score());
        assertTrue(batch.nextCursor() > initialCursor);

        long deliveredCursor = batch.nextCursor();
        projector.projectAnalyzed(updated);

        assertEquals(deliveredCursor, live.currentCursor());
        ResultLiveCriteria noFilters = new ResultLiveCriteria(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertTrue(live.pollAfter(deliveredCursor, noFilters, 10).updates().isEmpty());
        assertEquals(deliveredCursor, live.pollAfter(Long.MAX_VALUE, noFilters, 10).nextCursor());
    }

    /**
     * Load detailed content, attributes, tags, provenance, and return empty for a missing profile-scoped item.
     */
    @Test
    void shouldLoadDetailAndReturnEmptyWhenMissing() {
        ResultQuery query = context.getBean(ResultQuery.class);

        ResultDetail detail = query.find(PROFILE_A, ITEM_A).orElseThrow();
        assertEquals("Java Kafka PostgreSQL", detail.normalizedContent());
        assertEquals(Map.of("location", "Remote", "organization", "Example Corp"), detail.attributes());
        assertEquals(List.of("java", "kafka"), detail.tags());
        assertEquals("analysis-a", detail.analysisEventId());
        assertEquals("run-a", detail.correlationId());
        assertTrue(detail.relevant());

        assertFalse(query.find(PROFILE_B, ITEM_A).isPresent());
    }

    private void seedResults() {
        AnalysisOutcomeProjector projector = context.getBean(AnalysisOutcomeProjector.class);
        projector.projectAnalyzed(result(
                "analysis-a", "source-event-a", "raw-a", ITEM_A, SOURCE_A, PROFILE_A, "JOB", true, "MATCHED", 90,
                Instant.parse("2026-09-13T10:10:00Z"), List.of("java", "kafka"),
                Map.of("location", "Remote", "organization", "Example Corp"), "run-a"));
        projector.projectAnalyzed(result(
                "analysis-b", "source-event-b", "raw-b", ITEM_B, SOURCE_B, PROFILE_A, "NEWS", false, "UNMATCHED", 0,
                Instant.parse("2026-09-13T10:40:00Z"), List.of(), Map.of(), "run-b"));
        projector.projectAnalyzed(result(
                "analysis-c", "source-event-c", "raw-c", ITEM_C, SOURCE_A, PROFILE_B, "JOB", true, "MATCHED", 75,
                Instant.parse("2026-09-13T11:00:00Z"), List.of("java"), Map.of("location", "Berlin"), "run-c"));
    }

    private static AnalyzedResult result(
            String analysisEventId,
            String sourceEventId,
            String rawItemId,
            String normalizedItemId,
            String sourceId,
            String profileId,
            String category,
            boolean relevant,
            String classification,
            int score,
            Instant analyzedAt,
            List<String> tags,
            Map<String, String> attributes,
            String correlationId) {
        return new AnalyzedResult(
                analysisEventId,
                sourceEventId,
                rawItemId,
                normalizedItemId,
                sourceId,
                profileId,
                category,
                Optional.of("external-" + rawItemId),
                Optional.of("Title " + rawItemId),
                "https://example.test/items/" + rawItemId,
                "Java Kafka PostgreSQL",
                "text/plain",
                attributes,
                relevant,
                classification,
                score,
                tags,
                "Deterministic explanation",
                "keyword-v1",
                Optional.of(analyzedAt.minusSeconds(60)),
                analyzedAt,
                correlationId,
                Optional.empty());
    }

    private static void resetDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS results CASCADE");
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }
}
