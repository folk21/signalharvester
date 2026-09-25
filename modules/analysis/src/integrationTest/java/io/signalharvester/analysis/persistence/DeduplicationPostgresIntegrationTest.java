package io.signalharvester.analysis.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.analysis.application.AnalysisItemInspection;
import io.signalharvester.analysis.application.AnalysisItemInspectionQuery;
import io.signalharvester.analysis.model.NormalizedContentItem;
import io.signalharvester.testing.PostgresContainerSupport;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies durable behavior of {@link io.signalharvester.analysis.persistence.JdbiDeduplicationClaimRepository}
 * and {@link AnalysisItemInspectionService} against real PostgreSQL, including filtering and ordering.
 *
 * <p>Related specification: {@code backend-analysis-normalization-deduplication}.</p>
 *
 * <p>Features: {@code ANALYSIS.DEDUPLICATION}, {@code DIAGNOSTICS.ANALYSIS_INSPECTION}.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class DeduplicationPostgresIntegrationTest {

    private static final Instant BASE_TIME = Instant.parse("2026-09-10T18:00:00Z");
    private static final String PROFILE_A = "profile-a";
    private static final String PROFILE_B = "profile-b";
    private static final String SOURCE_A = "source-01";
    private static final String SOURCE_B = "source-02";
    private static final String RUN_ID = "run-01";
    private static final String NORMALIZED_ITEM_A = "a".repeat(64);
    private static final String NORMALIZED_ITEM_B = "b".repeat(64);
    private static final String NORMALIZED_ITEM_C = "c".repeat(64);
    private static final String RAW_ITEM_1 = "raw-01";
    private static final String RAW_ITEM_2 = "raw-02";
    private static final String RAW_ITEM_3 = "raw-03";
    private static final String SOURCE_EVENT_1 = "event-01";
    private static final String SOURCE_EVENT_2 = "event-02";
    private static final String SOURCE_EVENT_3 = "event-03";

    @Container
    private static final PostgreSQLContainer POSTGRES = PostgresContainerSupport.create();

    private ApplicationContext context;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        context = ApplicationContext.run(contextProperties());
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    /**
     * Deduplicate within profile and allow same logical item for another profile.
     */
    @Test
    void shouldDeduplicateWithinProfileAndAllowSameLogicalItemForAnotherProfile() throws Exception {
        DeduplicationClaimRepository repository = context.getBean(DeduplicationClaimRepository.class);
        @SuppressWarnings("unchecked")
        TransactionOperations<Connection> transactions = context.getBean(
                TransactionOperations.class, Qualifiers.byName("default"));
        Instant firstSeen = BASE_TIME;
        NormalizedContentItem first = item(PROFILE_A, RAW_ITEM_1, SOURCE_EVENT_1);
        NormalizedContentItem duplicate = item(PROFILE_A, RAW_ITEM_2, SOURCE_EVENT_2);
        NormalizedContentItem otherProfile = item(PROFILE_B, RAW_ITEM_3, SOURCE_EVENT_3);

        transactions.executeWrite(status -> {
            assertTrue(repository.tryClaim(first, firstSeen));
            assertFalse(repository.tryClaim(duplicate, firstSeen.plusSeconds(10)));
            repository.recordDuplicate(duplicate, firstSeen.plusSeconds(10));
            assertTrue(repository.tryClaim(otherProfile, firstSeen.plusSeconds(20)));
            return null;
        });

        AnalysisItemInspectionQuery inspection = context.getBean(AnalysisItemInspectionQuery.class);
        assertEquals(2, inspection.recent(10, Optional.empty(), Optional.empty()).size());
        AnalysisItemInspection inspected = inspection.find(
                PROFILE_A,
                NORMALIZED_ITEM_A).orElseThrow();
        assertEquals(2, inspected.discoveryCount());
        assertEquals(RAW_ITEM_2, inspected.lastRawItemId());

        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                        SELECT monitoring_profile_id, discovery_count, last_raw_item_id
                          FROM analysis.normalized_item_claims
                         ORDER BY monitoring_profile_id
                        """)) {
            assertTrue(resultSet.next());
            assertEquals(PROFILE_A, resultSet.getString("monitoring_profile_id"));
            assertEquals(2, resultSet.getLong("discovery_count"));
            assertEquals(RAW_ITEM_2, resultSet.getString("last_raw_item_id"));
            assertTrue(resultSet.next());
            assertEquals(PROFILE_B, resultSet.getString("monitoring_profile_id"));
            assertEquals(1, resultSet.getLong("discovery_count"));
            assertFalse(resultSet.next());
        }
    }

    /** Allow exactly one concurrent PostgreSQL claimant for the same profile-scoped normalized identity. */
    @Test
    void shouldAllowOnlyOneConcurrentClaimForSameProfileItem() throws Exception {
        try (ApplicationContext secondContext = ApplicationContext.run(contextProperties());
                ExecutorService executor = Executors.newFixedThreadPool(2)) {
            DeduplicationClaimRepository firstRepository = context.getBean(DeduplicationClaimRepository.class);
            DeduplicationClaimRepository secondRepository = secondContext.getBean(DeduplicationClaimRepository.class);
            TransactionOperations<Connection> firstTransactions = transactions(context);
            TransactionOperations<Connection> secondTransactions = transactions(secondContext);
            NormalizedContentItem item = item(PROFILE_A, RAW_ITEM_1, SOURCE_EVENT_1);
            CountDownLatch start = new CountDownLatch(1);

            Future<Boolean> first = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return firstTransactions.executeWrite(status -> firstRepository.tryClaim(item, BASE_TIME));
            });
            Future<Boolean> second = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return secondTransactions.executeWrite(
                        status -> secondRepository.tryClaim(item, BASE_TIME.plusMillis(1)));
            });

            start.countDown();
            boolean firstClaimed = first.get(10, TimeUnit.SECONDS);
            boolean secondClaimed = second.get(10, TimeUnit.SECONDS);

            assertEquals(1, (firstClaimed ? 1 : 0) + (secondClaimed ? 1 : 0));
            assertEquals(1L, sqlLong("SELECT count(*) FROM analysis.normalized_item_claims"));
        }
    }

    /**
     * Filter order and limit inspection queries.
     */
    @Test
    void shouldFilterOrderAndLimitInspectionQueries() {
        DeduplicationClaimRepository repository = context.getBean(DeduplicationClaimRepository.class);
        @SuppressWarnings("unchecked")
        TransactionOperations<Connection> transactions = context.getBean(
                TransactionOperations.class, Qualifiers.byName("default"));
        Instant base = BASE_TIME;

        transactions.executeWrite(status -> {
            assertTrue(repository.tryClaim(
                    item(
                            PROFILE_A,
                            NORMALIZED_ITEM_A,
                            SOURCE_A,
                            "raw-a",
                            "event-a"),
                    base));
            assertTrue(repository.tryClaim(
                    item(
                            PROFILE_A,
                            NORMALIZED_ITEM_B,
                            SOURCE_B,
                            "raw-b",
                            "event-b"),
                    base.plusSeconds(30)));
            assertTrue(repository.tryClaim(
                    item(
                            PROFILE_B,
                            NORMALIZED_ITEM_C,
                            SOURCE_A,
                            "raw-c",
                            "event-c"),
                    base.plusSeconds(20)));
            return null;
        });

        AnalysisItemInspectionQuery inspection = context.getBean(AnalysisItemInspectionQuery.class);

        assertEquals(
                List.of(
                        NORMALIZED_ITEM_B,
                        NORMALIZED_ITEM_C),
                normalizedIds(inspection.recent(2, Optional.empty(), Optional.empty())));
        assertEquals(
                List.of(
                        NORMALIZED_ITEM_B,
                        NORMALIZED_ITEM_A),
                normalizedIds(inspection.recent(10, Optional.of(PROFILE_A), Optional.empty())));
        assertEquals(
                List.of(
                        NORMALIZED_ITEM_C,
                        NORMALIZED_ITEM_A),
                normalizedIds(inspection.recent(10, Optional.empty(), Optional.of(SOURCE_A))));
        assertEquals(
                List.of(NORMALIZED_ITEM_C),
                normalizedIds(inspection.recent(10, Optional.of(PROFILE_B), Optional.of(SOURCE_A))));
        assertEquals(
                List.of(NORMALIZED_ITEM_B),
                normalizedIds(inspection.recent(1, Optional.empty(), Optional.empty())));
    }

    /**
     * Reject repository access outside application-owned transaction.
     */
    @Test
    void shouldRejectRepositoryAccessOutsideApplicationOwnedTransaction() {
        DeduplicationClaimRepository repository = context.getBean(DeduplicationClaimRepository.class);

        assertThrows(AnalysisPersistenceException.class, () -> repository.tryClaim(
                item(PROFILE_A, RAW_ITEM_1, SOURCE_EVENT_1),
                BASE_TIME));
    }

    private static NormalizedContentItem item(String profileId, String rawItemId, String sourceEventId) {
        return item(
                profileId,
                NORMALIZED_ITEM_A,
                SOURCE_A,
                rawItemId,
                sourceEventId);
    }

    private static NormalizedContentItem item(
            String profileId,
            String normalizedItemId,
            String sourceId,
            String rawItemId,
            String sourceEventId) {
        return new NormalizedContentItem(
                sourceEventId,
                RUN_ID,
                Optional.empty(),
                BASE_TIME,
                rawItemId,
                normalizedItemId,
                sourceId,
                profileId,
                "JOB",
                Optional.empty(),
                Optional.of("Senior Java Engineer"),
                URI.create("https://example.test/jobs/1"),
                "Java Kafka",
                "text/plain",
                Map.of(),
                Optional.empty());
    }

    private static List<String> normalizedIds(List<AnalysisItemInspection> items) {
        return items.stream().map(AnalysisItemInspection::normalizedItemId).toList();
    }

    @SuppressWarnings("unchecked")
    private static TransactionOperations<Connection> transactions(ApplicationContext applicationContext) {
        return applicationContext.getBean(TransactionOperations.class, Qualifiers.byName("default"));
    }

    private static Map<String, Object> contextProperties() {
        return Map.ofEntries(
                Map.entry("datasources.default.url", POSTGRES.getJdbcUrl()),
                Map.entry("datasources.default.username", POSTGRES.getUsername()),
                Map.entry("datasources.default.password", POSTGRES.getPassword()),
                Map.entry("datasources.default.driver-class-name", "org.postgresql.Driver"),
                Map.entry("flyway.datasources.default.enabled", true),
                Map.entry("flyway.datasources.default.locations[0]", "classpath:db/migration/analysis"),
                Map.entry("kafka.enabled", false),
                Map.entry("signalharvester.analysis.keyword-rules.keywords", List.of("java")),
                Map.entry("signalharvester.analysis.keyword-rules.minimum-matches", 1));
    }

    private static long sqlLong(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static void resetDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS analysis CASCADE");
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }
}
