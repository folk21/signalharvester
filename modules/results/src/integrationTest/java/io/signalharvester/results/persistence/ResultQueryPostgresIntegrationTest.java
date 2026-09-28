package io.signalharvester.results.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import io.signalharvester.results.application.AnalysisOutcomeProjector;
import io.signalharvester.results.application.InvalidResultQueryException;
import io.signalharvester.results.application.ResultDetail;
import io.signalharvester.results.application.ResultLiveBatch;
import io.signalharvester.results.application.ResultLiveCriteria;
import io.signalharvester.results.application.ResultLiveQuery;
import io.signalharvester.results.application.ResultPage;
import io.signalharvester.results.application.ResultQuery;
import io.signalharvester.results.application.ResultQueryCriteria;
import io.signalharvester.results.application.ResultSummary;
import io.signalharvester.results.model.AnalyzedResult;
import io.signalharvester.testing.PostgresContainerSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies {@link JdbiResultQueryRepository} filtering, keyset pagination, text search, indexes, and detailed child
 * projection loading against the real Results PostgreSQL schema.
 *
 * <p>Related features: {@code RESULTS.MATERIALIZATION}, {@code RESULTS.BROWSING}, {@code RESULTS.LIVE}.</p>
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
    private static final PostgreSQLContainer POSTGRES = PostgresContainerSupport.create();

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
        ResultQueryCriteria criteria = queryCriteria(10)
                .profile(PROFILE_A)
                .source(SOURCE_A)
                .category("JOB")
                .relevant(true)
                .classification("MATCHED")
                .analyzedFrom(Instant.parse("2026-09-13T10:00:00Z"))
                .analyzedTo(Instant.parse("2026-09-13T10:30:00Z"))
                .build();

        List<ResultSummary> results = query.browse(criteria).results();

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
        ResultQueryCriteria criteria = queryCriteria(2).build();

        List<ResultSummary> results = query.browse(criteria).results();

        assertEquals(List.of(ITEM_C, ITEM_B), results.stream().map(ResultSummary::normalizedItemId).toList());
    }

    /** Traverse deterministic keyset pages without repeats and allow the client to change only page size. */
    @Test
    void shouldTraverseKeysetPagesAndAllowLimitChange() {
        ResultQuery query = context.getBean(ResultQuery.class);
        ResultQueryCriteria firstCriteria = criteria(1, null, null);

        ResultPage first = query.browse(firstCriteria);
        String cursor = first.nextCursor().orElseThrow();
        ResultPage second = query.browse(criteria(2, cursor, null));

        assertEquals(List.of(ITEM_C), first.results().stream().map(ResultSummary::normalizedItemId).toList());
        assertEquals(List.of(ITEM_B, ITEM_A), second.results().stream().map(ResultSummary::normalizedItemId).toList());
        assertTrue(second.nextCursor().isEmpty());
    }

    /** Continue correctly across rows sharing analyzedAt by using profile and item identity as tie breakers. */
    @Test
    void shouldPaginateDeterministicallyAcrossAnalyzedAtTies() {
        AnalysisOutcomeProjector projector = context.getBean(AnalysisOutcomeProjector.class);
        Instant tiedAt = Instant.parse("2026-09-13T10:40:00Z");
        String itemD = "d".repeat(64);
        String itemE = "e".repeat(64);
        projector.projectAnalyzed(analyzedResult("analysis-d", "source-event-d", "raw-d", itemD)
                .score(70)
                .analyzedAt(tiedAt)
                .tags(List.of("java"))
                .correlationId("run-d")
                .build());
        projector.projectAnalyzed(analyzedResult("analysis-e", "source-event-e", "raw-e", itemE)
                .profile(PROFILE_B)
                .score(65)
                .analyzedAt(tiedAt)
                .tags(List.of("java"))
                .correlationId("run-e")
                .build());

        ResultQuery query = context.getBean(ResultQuery.class);
        ResultPage first = query.browse(criteria(2, null, null));
        ResultPage second = query.browse(criteria(2, first.nextCursor().orElse(null), null));
        ResultPage third = query.browse(criteria(2, second.nextCursor().orElse(null), null));

        assertEquals(List.of(ITEM_C, ITEM_B), ids(first));
        assertEquals(List.of(itemD, itemE), ids(second));
        assertEquals(List.of(ITEM_A), ids(third));
        assertTrue(third.nextCursor().isEmpty());
    }

    /** Search indexed title/content text and compose search with filters, pagination, and cursor validation. */
    @Test
    void shouldSearchResultsAndBindCursorToQueryCriteria() {
        AnalysisOutcomeProjector projector = context.getBean(AnalysisOutcomeProjector.class);
        projector.projectAnalyzed(analyzedResult("analysis-a-search", "source-event-a-search", "raw-a-search", ITEM_A)
                .score(91)
                .analyzedAt(Instant.parse("2026-09-13T12:00:00Z"))
                .title("Senior Java Platform Engineer")
                .content("Distributed systems and stream processing")
                .correlationId("run-a-search")
                .build());
        projector.projectAnalyzed(analyzedResult("analysis-c-search", "source-event-c-search", "raw-c-search", ITEM_C)
                .profile(PROFILE_B)
                .score(76)
                .analyzedAt(Instant.parse("2026-09-13T11:30:00Z"))
                .title("Kafka Platform Engineer")
                .content("Distributed systems observability")
                .correlationId("run-c-search")
                .build());

        ResultQuery query = context.getBean(ResultQuery.class);
        ResultQueryCriteria firstCriteria = criteria(1, null, "distributed systems");
        ResultPage first = query.browse(firstCriteria);
        ResultPage second = query.browse(criteria(1, first.nextCursor().orElse(null), "distributed systems"));

        assertEquals(List.of(ITEM_A), ids(first));
        assertEquals(List.of(ITEM_C), ids(second));
        assertTrue(second.nextCursor().isEmpty());

        ResultQueryCriteria sourceBSearch = queryCriteria(10)
                .source(SOURCE_B)
                .search("distributed systems")
                .build();
        assertTrue(query.browse(sourceBSearch).results().isEmpty());

        String cursor = first.nextCursor().orElseThrow();
        ResultQueryCriteria changedSearch = criteria(1, cursor, "kafka");
        assertThrows(InvalidResultQueryException.class, () -> query.browse(changedSearch));
    }

    /** Create the composite browse-order and full-text indexes through Results-owned Flyway migrations. */
    @Test
    void shouldCreateProductionBrowsingIndexes() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                var rows = statement.executeQuery("""
                        SELECT indexname
                          FROM pg_indexes
                         WHERE schemaname = 'results'
                           AND indexname IN ('idx_results_analyzed_items_browse_order',
                                             'idx_results_analyzed_items_search')
                         ORDER BY indexname
                        """)) {
            List<String> indexes = new java.util.ArrayList<>();
            while (rows.next()) {
                indexes.add(rows.getString(1));
            }
            assertEquals(List.of(
                    "idx_results_analyzed_items_browse_order",
                    "idx_results_analyzed_items_search"), indexes);
        }
    }

    /**
     * Resume live polling from a durable cursor and do not advance it for redelivery of the same analysis event.
     */
    @Test
    void shouldPollLiveUpdatesAndKeepDuplicateAnalysisEventIdempotent() {
        ResultLiveQuery live = context.getBean(ResultLiveQuery.class);
        AnalysisOutcomeProjector projector = context.getBean(AnalysisOutcomeProjector.class);
        long initialCursor = live.currentCursor();

        AnalyzedResult updated = analyzedResult("analysis-a-2", "source-event-a-2", "raw-a-2", ITEM_A)
                .score(95)
                .analyzedAt(Instant.parse("2026-09-13T11:10:00Z"))
                .tags(List.of("java", "kafka", "postgresql"))
                .attributes(Map.of("location", "Remote"))
                .correlationId("run-a-2")
                .build();
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

    /** Preserve the application-owned transaction boundary for direct Jdbi persistence adapter access. */
    @Test
    void shouldRejectDirectRepositoryAccessOutsideApplicationOwnedTransaction() {
        JdbiResultQueryRepository queryRepository = context.getBean(JdbiResultQueryRepository.class);
        JdbiResultProjectionRepository projectionRepository = context.getBean(JdbiResultProjectionRepository.class);
        AnalyzedResult result = analyzedResult(
                        "analysis-direct", "source-event-direct", "raw-direct", "d".repeat(64))
                .analyzedAt(Instant.parse("2026-09-13T12:30:00Z"))
                .tags(List.of("java"))
                .correlationId("run-direct")
                .build();

        assertThrows(ResultsPersistenceException.class, queryRepository::currentCursor);
        assertThrows(ResultsPersistenceException.class, () -> projectionRepository.upsertAnalyzed(result));
    }

    /** Roll back the parent projection and live cursor when a child collection write fails. */
    @Test
    void shouldRollbackAnalyzedProjectionWhenChildWriteFails() throws Exception {
        String normalizedItemId = "d".repeat(64);
        // PostgreSQL text rejects zero bytes, forcing the child tag write to fail without schema DDL or lock waits.
        String postgresInvalidTag = "force" + (char) 0 + "persistence-failure";
        AnalyzedResult failing = analyzedResult(
                        "analysis-rollback", "source-event-rollback", "raw-rollback", normalizedItemId)
                .analyzedAt(Instant.parse("2026-09-13T12:30:00Z"))
                .tags(List.of(postgresInvalidTag))
                .attributes(Map.of("location", "Remote"))
                .correlationId("run-rollback")
                .build();

        AnalysisOutcomeProjector projector = context.getBean(AnalysisOutcomeProjector.class);
        assertThrows(ResultsPersistenceException.class, () -> projector.projectAnalyzed(failing));

        assertEquals(0L, sqlLong("""
                SELECT count(*)
                  FROM results.analyzed_items
                 WHERE monitoring_profile_id = 'profile-a'
                   AND normalized_item_id = 'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd'
                """));
        assertEquals(0L, sqlLong("""
                SELECT count(*)
                  FROM results.analyzed_item_attributes
                 WHERE monitoring_profile_id = 'profile-a'
                   AND normalized_item_id = 'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd'
                """));
        assertEquals(0L, sqlLong("""
                SELECT count(*)
                  FROM results.analyzed_item_tags
                 WHERE monitoring_profile_id = 'profile-a'
                   AND normalized_item_id = 'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd'
                """));
        assertEquals(0L, sqlLong("""
                SELECT count(*)
                  FROM results.live_result_cursors
                 WHERE monitoring_profile_id = 'profile-a'
                   AND normalized_item_id = 'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd'
                """));
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

    private static ResultQueryCriteria criteria(int limit, String cursor, String search) {
        return queryCriteria(limit).cursor(cursor).search(search).build();
    }

    private static ResultQueryCriteriaBuilder queryCriteria(int limit) {
        return new ResultQueryCriteriaBuilder(limit);
    }

    private static List<String> ids(ResultPage page) {
        return page.results().stream().map(ResultSummary::normalizedItemId).toList();
    }

    private void seedResults() {
        AnalysisOutcomeProjector projector = context.getBean(AnalysisOutcomeProjector.class);
        projector.projectAnalyzed(analyzedResult("analysis-a", "source-event-a", "raw-a", ITEM_A)
                .score(90)
                .analyzedAt(Instant.parse("2026-09-13T10:10:00Z"))
                .tags(List.of("java", "kafka"))
                .attributes(Map.of("location", "Remote", "organization", "Example Corp"))
                .correlationId("run-a")
                .build());
        projector.projectAnalyzed(analyzedResult("analysis-b", "source-event-b", "raw-b", ITEM_B)
                .source(SOURCE_B)
                .category("NEWS")
                .relevant(false)
                .classification("UNMATCHED")
                .score(0)
                .analyzedAt(Instant.parse("2026-09-13T10:40:00Z"))
                .tags(List.of())
                .correlationId("run-b")
                .build());
        projector.projectAnalyzed(analyzedResult("analysis-c", "source-event-c", "raw-c", ITEM_C)
                .profile(PROFILE_B)
                .score(75)
                .analyzedAt(Instant.parse("2026-09-13T11:00:00Z"))
                .tags(List.of("java"))
                .attributes(Map.of("location", "Berlin"))
                .correlationId("run-c")
                .build());
    }

    private static AnalyzedResultBuilder analyzedResult(
            String analysisEventId, String sourceEventId, String rawItemId, String normalizedItemId) {
        return new AnalyzedResultBuilder(analysisEventId, sourceEventId, rawItemId, normalizedItemId);
    }

    private static final class AnalyzedResultBuilder {
        private final String analysisEventId;
        private final String sourceEventId;
        private final String rawItemId;
        private final String normalizedItemId;
        private String sourceId = SOURCE_A;
        private String profileId = PROFILE_A;
        private String category = "JOB";
        private boolean relevant = true;
        private String classification = "MATCHED";
        private int score = 50;
        private Instant analyzedAt = Instant.parse("2026-09-13T12:00:00Z");
        private List<String> tags = List.of("java");
        private Map<String, String> attributes = Map.of();
        private String title;
        private String content = "Java Kafka PostgreSQL";
        private String correlationId = "run-default";

        private AnalyzedResultBuilder(
                String analysisEventId, String sourceEventId, String rawItemId, String normalizedItemId) {
            this.analysisEventId = Objects.requireNonNull(analysisEventId, "analysisEventId");
            this.sourceEventId = Objects.requireNonNull(sourceEventId, "sourceEventId");
            this.rawItemId = Objects.requireNonNull(rawItemId, "rawItemId");
            this.normalizedItemId = Objects.requireNonNull(normalizedItemId, "normalizedItemId");
            this.title = "Title " + rawItemId;
        }

        private AnalyzedResultBuilder source(String value) {
            sourceId = Objects.requireNonNull(value, "sourceId");
            return this;
        }

        private AnalyzedResultBuilder profile(String value) {
            profileId = Objects.requireNonNull(value, "profileId");
            return this;
        }

        private AnalyzedResultBuilder category(String value) {
            category = Objects.requireNonNull(value, "category");
            return this;
        }

        private AnalyzedResultBuilder relevant(boolean value) {
            relevant = value;
            return this;
        }

        private AnalyzedResultBuilder classification(String value) {
            classification = Objects.requireNonNull(value, "classification");
            return this;
        }

        private AnalyzedResultBuilder score(int value) {
            score = value;
            return this;
        }

        private AnalyzedResultBuilder analyzedAt(Instant value) {
            analyzedAt = Objects.requireNonNull(value, "analyzedAt");
            return this;
        }

        private AnalyzedResultBuilder tags(List<String> value) {
            tags = Objects.requireNonNull(value, "tags");
            return this;
        }

        private AnalyzedResultBuilder attributes(Map<String, String> value) {
            attributes = Objects.requireNonNull(value, "attributes");
            return this;
        }

        private AnalyzedResultBuilder title(String value) {
            title = Objects.requireNonNull(value, "title");
            return this;
        }

        private AnalyzedResultBuilder content(String value) {
            content = Objects.requireNonNull(value, "content");
            return this;
        }

        private AnalyzedResultBuilder correlationId(String value) {
            correlationId = Objects.requireNonNull(value, "correlationId");
            return this;
        }

        private AnalyzedResult build() {
            return new AnalyzedResult(
                    analysisEventId,
                    sourceEventId,
                    rawItemId,
                    normalizedItemId,
                    sourceId,
                    profileId,
                    category,
                    Optional.of("external-" + rawItemId),
                    Optional.of(title),
                    "https://example.test/items/" + rawItemId,
                    content,
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
    }

    private static final class ResultQueryCriteriaBuilder {
        private final int limit;
        private String monitoringProfileId;
        private String sourceId;
        private String informationCategory;
        private Boolean relevant;
        private String classification;
        private Instant analyzedFrom;
        private Instant analyzedTo;
        private String search;
        private String cursor;

        private ResultQueryCriteriaBuilder(int limit) {
            this.limit = limit;
        }

        private ResultQueryCriteriaBuilder profile(String value) {
            monitoringProfileId = value;
            return this;
        }

        private ResultQueryCriteriaBuilder source(String value) {
            sourceId = value;
            return this;
        }

        private ResultQueryCriteriaBuilder category(String value) {
            informationCategory = value;
            return this;
        }

        private ResultQueryCriteriaBuilder relevant(boolean value) {
            relevant = value;
            return this;
        }

        private ResultQueryCriteriaBuilder classification(String value) {
            classification = value;
            return this;
        }

        private ResultQueryCriteriaBuilder analyzedFrom(Instant value) {
            analyzedFrom = value;
            return this;
        }

        private ResultQueryCriteriaBuilder analyzedTo(Instant value) {
            analyzedTo = value;
            return this;
        }

        private ResultQueryCriteriaBuilder search(String value) {
            search = value;
            return this;
        }

        private ResultQueryCriteriaBuilder cursor(String value) {
            cursor = value;
            return this;
        }

        private ResultQueryCriteria build() {
            return new ResultQueryCriteria(
                    limit,
                    Optional.ofNullable(monitoringProfileId),
                    Optional.ofNullable(sourceId),
                    Optional.ofNullable(informationCategory),
                    Optional.ofNullable(relevant),
                    Optional.ofNullable(classification),
                    Optional.ofNullable(analyzedFrom),
                    Optional.ofNullable(analyzedTo),
                    Optional.ofNullable(search),
                    Optional.ofNullable(cursor));
        }
    }

    private static long sqlLong(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
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
