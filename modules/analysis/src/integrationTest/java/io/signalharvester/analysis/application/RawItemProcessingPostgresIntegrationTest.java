package io.signalharvester.analysis.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.analysis.event.AnalysisEventPublisher;
import io.signalharvester.analysis.event.AnalysisPublicationException;
import io.signalharvester.analysis.event.AnalysisPublicationResult;
import io.signalharvester.analysis.event.AnalyzedItem;
import io.signalharvester.analysis.event.RejectedItem;
import io.signalharvester.analysis.model.DiscoveredRawItem;
import io.signalharvester.analysis.normalization.ContentNormalizer;
import io.signalharvester.analysis.persistence.DeduplicationClaimRepository;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies {@link RawItemProcessingService} transaction guarantees against real PostgreSQL, including
 * deduplication rollback when terminal event publication fails.
 *
 * <p>Related specification: {@code backend-analysis-normalization-deduplication}.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class RawItemProcessingPostgresIntegrationTest {

    private static final Instant PROCESSING_TIME = Instant.parse("2026-09-13T08:00:00Z");
    private static final Instant DISCOVERED_AT = Instant.parse("2026-09-13T07:59:00Z");
    private static final Instant PUBLISHED_AT = Instant.parse("2026-09-13T07:55:00Z");
    private static final String DEFAULT_PROFILE_ID = "profile-01";
    private static final String PROFILE_A = "profile-a";
    private static final String PROFILE_B = "profile-b";
    private static final String SOURCE_ID = "source-01";
    private static final String RUN_ID = "run-01";
    private static final String TEST_ANALYZER = "test-analyzer";
    private static final String RAW_ITEM_1 = "raw-01";
    private static final String RAW_ITEM_2 = "raw-02";
    private static final String SOURCE_EVENT_1 = "source-event-01";
    private static final String SOURCE_EVENT_2 = "source-event-02";
    private static final String TRACEPARENT =
            "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("signalharvester")
            .withUsername("signalharvester")
            .withPassword("signalharvester");

    private ApplicationContext context;
    private ContentNormalizer normalizer;
    private DeduplicationClaimRepository repository;
    private AnalysisItemInspectionQuery inspection;
    private TransactionOperations<Connection> transactions;

    @BeforeEach
    void setUp() throws Exception {
        resetDatabase();
        context = ApplicationContext.run(Map.ofEntries(
                Map.entry("datasources.default.url", POSTGRES.getJdbcUrl()),
                Map.entry("datasources.default.username", POSTGRES.getUsername()),
                Map.entry("datasources.default.password", POSTGRES.getPassword()),
                Map.entry("datasources.default.driver-class-name", "org.postgresql.Driver"),
                Map.entry("flyway.datasources.default.enabled", true),
                Map.entry("flyway.datasources.default.locations[0]", "classpath:db/migration/analysis"),
                Map.entry("kafka.enabled", false),
                Map.entry("signalharvester.analysis.keyword-rules.keywords", List.of("java", "kafka")),
                Map.entry("signalharvester.analysis.keyword-rules.minimum-matches", 1)));

        normalizer = context.getBean(ContentNormalizer.class);
        repository = context.getBean(DeduplicationClaimRepository.class);
        inspection = context.getBean(AnalysisItemInspectionQuery.class);
        @SuppressWarnings("unchecked")
        TransactionOperations<Connection> defaultTransactions = context.getBean(
                TransactionOperations.class, Qualifiers.byName("default"));
        transactions = defaultTransactions;
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    /**
     * Publish analyzed for valid irrelevant item and persist claim.
     */
    @Test
    void shouldPublishAnalyzedForValidIrrelevantItemAndPersistClaim() {
        RecordingPublisher publisher = new RecordingPublisher();
        ContentAnalyzer analyzer = item -> new AnalysisDecision(
                false,
                "NO_KEYWORD_MATCH",
                0,
                List.of(),
                "No configured keywords matched",
                TEST_ANALYZER);
        RawItemProcessingService service = service(analyzer, publisher);
        DiscoveredRawItem rawItem = rawItem(RAW_ITEM_1, SOURCE_EVENT_1, "Unrelated content");

        RawItemProcessingResult result = service.process(rawItem);

        assertEquals(RawItemProcessingStatus.ANALYZED, result.status());
        assertTrue(publisher.analyzed().isPresent());
        assertFalse(publisher.analyzed().orElseThrow().decision().relevant());
        assertTrue(publisher.rejected().isEmpty());

        String normalizedItemId = normalizer.normalize(rawItem).normalizedItemId();
        AnalysisItemInspection persisted = inspection.find(DEFAULT_PROFILE_ID, normalizedItemId).orElseThrow();
        assertEquals(1, persisted.discoveryCount());
        assertEquals(RAW_ITEM_1, persisted.lastRawItemId());
    }

    /**
     * Reject duplicate without running analyzer again.
     */
    @Test
    void shouldRejectDuplicateWithoutRunningAnalyzerAgain() {
        AtomicInteger analyzerCalls = new AtomicInteger();
        ContentAnalyzer analyzer = item -> {
            analyzerCalls.incrementAndGet();
            return new AnalysisDecision(
                    true,
                    "MATCHED_KEYWORDS",
                    100,
                    List.of("java"),
                    "Matched test keyword",
                    TEST_ANALYZER);
        };
        RecordingPublisher publisher = new RecordingPublisher();
        RawItemProcessingService service = service(analyzer, publisher);
        DiscoveredRawItem first = rawItem(RAW_ITEM_1, SOURCE_EVENT_1, "Java Kafka");
        DiscoveredRawItem duplicate = rawItem(RAW_ITEM_2, SOURCE_EVENT_2, "Java   Kafka");

        RawItemProcessingResult firstResult = service.process(first);
        RawItemProcessingResult duplicateResult = service.process(duplicate);

        assertEquals(RawItemProcessingStatus.ANALYZED, firstResult.status());
        assertEquals(RawItemProcessingStatus.DUPLICATE, duplicateResult.status());
        assertEquals(1, analyzerCalls.get());
        assertTrue(publisher.rejected().isPresent());
        assertEquals("DUPLICATE", publisher.rejected().orElseThrow().reasonCode());

        String normalizedItemId = normalizer.normalize(first).normalizedItemId();
        AnalysisItemInspection persisted = inspection.find(DEFAULT_PROFILE_ID, normalizedItemId).orElseThrow();
        assertEquals(2, persisted.discoveryCount());
        assertEquals(RAW_ITEM_2, persisted.lastRawItemId());
        assertEquals(SOURCE_EVENT_2, persisted.lastSourceEventId());
    }

    /**
     * Analyze same logical item independently for different profiles.
     */
    @Test
    void shouldAnalyzeSameLogicalItemIndependentlyForDifferentProfiles() {
        AtomicInteger analyzerCalls = new AtomicInteger();
        ContentAnalyzer analyzer = item -> {
            analyzerCalls.incrementAndGet();
            return new AnalysisDecision(
                    true,
                    "MATCHED_KEYWORDS",
                    100,
                    List.of("java"),
                    "Matched test keyword",
                    TEST_ANALYZER);
        };
        RecordingPublisher publisher = new RecordingPublisher();
        RawItemProcessingService service = service(analyzer, publisher);
        DiscoveredRawItem firstProfile = rawItem(PROFILE_A, RAW_ITEM_1, SOURCE_EVENT_1, "Java Kafka");
        DiscoveredRawItem secondProfile = rawItem(PROFILE_B, RAW_ITEM_2, SOURCE_EVENT_2, "Java Kafka");

        RawItemProcessingResult firstResult = service.process(firstProfile);
        RawItemProcessingResult secondResult = service.process(secondProfile);

        assertEquals(RawItemProcessingStatus.ANALYZED, firstResult.status());
        assertEquals(RawItemProcessingStatus.ANALYZED, secondResult.status());
        assertEquals(firstResult.normalizedItemId(), secondResult.normalizedItemId());
        assertEquals(2, analyzerCalls.get());
        assertTrue(inspection.find(PROFILE_A, firstResult.normalizedItemId()).isPresent());
        assertTrue(inspection.find(PROFILE_B, secondResult.normalizedItemId()).isPresent());
    }

    /** Roll back a new deduplication claim when terminal analyzed publication fails. */
    @Test
    void shouldRollbackNewClaimWhenAnalyzedPublicationFails() {
        RecordingPublisher publisher = new RecordingPublisher();
        publisher.failAnalyzed();
        RawItemProcessingService service = service(matchingAnalyzer(), publisher);
        DiscoveredRawItem rawItem = rawItem(RAW_ITEM_1, SOURCE_EVENT_1, "Java Kafka");
        String normalizedItemId = normalizer.normalize(rawItem).normalizedItemId();

        assertThrows(AnalysisPublicationException.class, () -> service.process(rawItem));

        assertTrue(inspection.find(DEFAULT_PROFILE_ID, normalizedItemId).isEmpty());
    }

    /**
     * Rollback duplicate observation when rejected publication fails.
     */
    @Test
    void shouldRollbackDuplicateObservationWhenRejectedPublicationFails() {
        RecordingPublisher publisher = new RecordingPublisher();
        RawItemProcessingService service = service(matchingAnalyzer(), publisher);
        DiscoveredRawItem first = rawItem(RAW_ITEM_1, SOURCE_EVENT_1, "Java Kafka");
        DiscoveredRawItem duplicate = rawItem(RAW_ITEM_2, SOURCE_EVENT_2, "Java Kafka");
        String normalizedItemId = normalizer.normalize(first).normalizedItemId();

        service.process(first);
        publisher.failRejected();

        assertThrows(AnalysisPublicationException.class, () -> service.process(duplicate));

        AnalysisItemInspection persisted = inspection.find(DEFAULT_PROFILE_ID, normalizedItemId).orElseThrow();
        assertEquals(1, persisted.discoveryCount());
        assertEquals(RAW_ITEM_1, persisted.lastRawItemId());
        assertEquals(SOURCE_EVENT_1, persisted.lastSourceEventId());
    }

    private RawItemProcessingService service(ContentAnalyzer analyzer, AnalysisEventPublisher publisher) {
        return new RawItemProcessingService(
                normalizer,
                repository,
                analyzer,
                publisher,
                transactions,
                Clock.fixed(PROCESSING_TIME, ZoneOffset.UTC));
    }

    private static ContentAnalyzer matchingAnalyzer() {
        return item -> new AnalysisDecision(
                true,
                "MATCHED_KEYWORDS",
                100,
                List.of("java", "kafka"),
                "Matched test keywords",
                TEST_ANALYZER);
    }

    private static DiscoveredRawItem rawItem(String rawItemId, String sourceEventId, String content) {
        return rawItem(DEFAULT_PROFILE_ID, rawItemId, sourceEventId, content);
    }

    private static DiscoveredRawItem rawItem(
            String profileId, String rawItemId, String sourceEventId, String content) {
        return new DiscoveredRawItem(
                sourceEventId,
                RUN_ID,
                Optional.of(TRACEPARENT),
                DISCOVERED_AT,
                rawItemId,
                SOURCE_ID,
                profileId,
                "JOB",
                Optional.of("external-job-01"),
                Optional.of("Senior Java Engineer"),
                URI.create("https://example.test/jobs/1"),
                content,
                "text/plain",
                Optional.of(PUBLISHED_AT));
    }

    private static void resetDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS analysis CASCADE");
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }

    private static final class RecordingPublisher implements AnalysisEventPublisher {
        private final AtomicReference<AnalyzedItem> analyzed = new AtomicReference<>();
        private final AtomicReference<RejectedItem> rejected = new AtomicReference<>();
        private boolean failAnalyzed;
        private boolean failRejected;

        @Override
        public AnalysisPublicationResult publishAnalyzed(AnalyzedItem analyzedItem) {
            if (failAnalyzed) {
                throw publicationFailure(analyzedItem.item().normalizedItemId(), "analyzed-items");
            }
            analyzed.set(analyzedItem);
            return new AnalysisPublicationResult(
                    "analysis-event-01", "analyzed-items", analyzedItem.item().normalizedItemId());
        }

        @Override
        public AnalysisPublicationResult publishRejected(RejectedItem rejectedItem) {
            if (failRejected) {
                throw publicationFailure(rejectedItem.item().normalizedItemId(), "rejected-items");
            }
            rejected.set(rejectedItem);
            return new AnalysisPublicationResult(
                    "rejection-event-01", "rejected-items", rejectedItem.item().normalizedItemId());
        }

        void failAnalyzed() {
            failAnalyzed = true;
        }

        void failRejected() {
            failRejected = true;
        }

        Optional<AnalyzedItem> analyzed() {
            return Optional.ofNullable(analyzed.get());
        }

        Optional<RejectedItem> rejected() {
            return Optional.ofNullable(rejected.get());
        }

        private static AnalysisPublicationException publicationFailure(String normalizedItemId, String topic) {
            return new AnalysisPublicationException(
                    normalizedItemId,
                    topic,
                    "simulated terminal publication failure",
                    new IllegalStateException("broker unavailable"));
        }
    }
}
