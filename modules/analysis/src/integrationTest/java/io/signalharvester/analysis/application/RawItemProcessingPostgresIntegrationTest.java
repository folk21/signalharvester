package io.signalharvester.analysis.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.analysis.event.AnalysisPublicationResult;
import io.signalharvester.analysis.outbox.AnalysisOutbox;
import io.signalharvester.analysis.outbox.AnalysisOutboxBacklog;
import io.signalharvester.analysis.outbox.AnalysisOutboxPersistenceException;
import io.signalharvester.analysis.outbox.AnalysisOutboxDispatcher;
import io.signalharvester.analysis.outbox.AnalysisOutboxEntry;
import io.signalharvester.analysis.outbox.AnalysisOutboxStore;
import io.signalharvester.analysis.configuration.AnalysisOutboxConfiguration;
import io.signalharvester.analysis.event.kafka.AnalysisKafkaClient;
import io.signalharvester.analysis.event.AnalyzedItem;
import io.signalharvester.analysis.event.RejectedItem;
import io.signalharvester.analysis.model.DiscoveredRawItem;
import io.signalharvester.analysis.model.KeywordAnalysisSettings;
import io.signalharvester.analysis.observability.AnalysisObservability;
import io.signalharvester.analysis.normalization.ContentNormalizer;
import io.signalharvester.analysis.persistence.DeduplicationClaimRepository;
import io.signalharvester.events.analysis.v1.ItemAnalyzed;
import io.signalharvester.testing.PostgresContainerSupport;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * deduplication rollback when transactional outbox staging fails.
 *
 * <p>Related specifications: {@code backend-analysis-normalization-deduplication},
 * {@code backend-db-kafka-consistency}, {@code backend-analysis-outbox-lease-renewal},
 * {@code backend-analysis-outbox-interruption-fencing},
 * {@code backend-analysis-outbox-expired-lease-fencing}, and
 * {@code backend-analysis-outbox-failure-lease-fencing}.</p>
 *
 * <p>Features: {@code ANALYSIS.DEDUPLICATION}, {@code ANALYSIS.CLASSIFICATION}, {@code ANALYSIS.OUTBOX}.</p>
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
    private static final PostgreSQLContainer POSTGRES = PostgresContainerSupport.create();

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
     * Stage analyzed event for valid irrelevant item and persist claim.
     */
    @Test
    void shouldPublishAnalyzedForValidIrrelevantItemAndPersistClaim() {
        RecordingOutbox outbox = new RecordingOutbox();
        ContentAnalyzer analyzer = (item, settings) -> new AnalysisDecision(
                false,
                "NO_KEYWORD_MATCH",
                0,
                List.of(),
                "No configured keywords matched",
                TEST_ANALYZER);
        RawItemProcessingService service = service(analyzer, outbox);
        DiscoveredRawItem rawItem = rawItem(RAW_ITEM_1, SOURCE_EVENT_1, "Unrelated content");

        RawItemProcessingResult result = service.process(rawItem);

        assertEquals(RawItemProcessingStatus.ANALYZED, result.status());
        assertTrue(outbox.analyzed().isPresent());
        assertFalse(outbox.analyzed().orElseThrow().decision().relevant());
        assertTrue(outbox.rejected().isEmpty());

        String normalizedItemId = normalizer.normalize(rawItem).normalizedItemId();
        AnalysisItemInspection persisted = inspection.find(DEFAULT_PROFILE_ID, normalizedItemId).orElseThrow();
        assertEquals(1, persisted.discoveryCount());
        assertEquals(RAW_ITEM_1, persisted.lastRawItemId());
    }

    /**
     * Stage duplicate rejection without running analyzer again.
     */
    @Test
    void shouldRejectDuplicateWithoutRunningAnalyzerAgain() {
        AtomicInteger analyzerCalls = new AtomicInteger();
        ContentAnalyzer analyzer = (item, settings) -> {
            analyzerCalls.incrementAndGet();
            return new AnalysisDecision(
                    true,
                    "MATCHED_KEYWORDS",
                    100,
                    List.of("java"),
                    "Matched test keyword",
                    TEST_ANALYZER);
        };
        RecordingOutbox outbox = new RecordingOutbox();
        RawItemProcessingService service = service(analyzer, outbox);
        DiscoveredRawItem first = rawItem(RAW_ITEM_1, SOURCE_EVENT_1, "Java Kafka");
        DiscoveredRawItem duplicate = rawItem(RAW_ITEM_2, SOURCE_EVENT_2, "Java   Kafka");

        RawItemProcessingResult firstResult = service.process(first);
        RawItemProcessingResult duplicateResult = service.process(duplicate);

        assertEquals(RawItemProcessingStatus.ANALYZED, firstResult.status());
        assertEquals(RawItemProcessingStatus.DUPLICATE, duplicateResult.status());
        assertEquals(1, analyzerCalls.get());
        assertTrue(outbox.rejected().isPresent());
        assertEquals("DUPLICATE", outbox.rejected().orElseThrow().reasonCode());

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
        ContentAnalyzer analyzer = (item, settings) -> {
            analyzerCalls.incrementAndGet();
            return new AnalysisDecision(
                    true,
                    "MATCHED_KEYWORDS",
                    100,
                    List.of("java"),
                    "Matched test keyword",
                    TEST_ANALYZER);
        };
        RecordingOutbox outbox = new RecordingOutbox();
        RawItemProcessingService service = service(analyzer, outbox);
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

    /** Commit the deduplication claim and serialized terminal event in the same database transaction. */
    @Test
    void shouldCommitClaimAndOutboxEntryTogether() throws Exception {
        AnalysisOutbox transactionalOutbox = context.getBean(AnalysisOutbox.class);
        RawItemProcessingService service = service(matchingAnalyzer(), transactionalOutbox);
        DiscoveredRawItem rawItem = rawItem(RAW_ITEM_1, SOURCE_EVENT_1, "Java Kafka");

        RawItemProcessingResult result = service.process(rawItem);

        assertEquals(1, outboxRowCount());
        assertEquals(result.eventId(), outboxEventId());
        assertEquals(TRACEPARENT, outboxTraceparent());
        assertTrue(inspection.find(DEFAULT_PROFILE_ID, result.normalizedItemId()).isPresent());
    }

    /** Expose pending count and oldest row timestamp for outbox capacity telemetry. */
    @Test
    void shouldInspectOutboxBacklog() {
        AnalysisOutbox transactionalOutbox = context.getBean(AnalysisOutbox.class);
        RawItemProcessingService service = service(matchingAnalyzer(), transactionalOutbox);
        service.process(rawItem(RAW_ITEM_1, SOURCE_EVENT_1, "Java Kafka"));
        AnalysisOutboxStore store = context.getBean(AnalysisOutboxStore.class);

        AnalysisOutboxBacklog pending = transactions.executeRead(status -> store.inspectBacklog());

        assertEquals(1L, pending.pendingCount());
        assertTrue(pending.oldestCreatedAt().isPresent());

        dispatcher((topic, key, payload) -> { }, Instant.parse("2026-09-15T12:05:00Z")).dispatchAvailable();

        AnalysisOutboxBacklog drained = transactions.executeRead(status -> store.inspectBacklog());
        assertEquals(0L, drained.pendingCount());
        assertTrue(drained.oldestCreatedAt().isEmpty());
    }

    /** Prevent a second replica from claiming a live lease and allow recovery after lease expiry. */
    @Test
    void shouldCoordinateOutboxClaimsWithExpiringLeases() {
        AnalysisOutbox transactionalOutbox = context.getBean(AnalysisOutbox.class);
        RawItemProcessingService service = service(matchingAnalyzer(), transactionalOutbox);
        service.process(rawItem(RAW_ITEM_1, SOURCE_EVENT_1, "Java Kafka"));
        AnalysisOutboxStore store = context.getBean(AnalysisOutboxStore.class);
        Instant now = Instant.parse("2026-09-15T12:05:00Z");
        UUID firstLease = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID secondLease = UUID.fromString("22222222-2222-2222-2222-222222222222");

        var firstClaim = transactions.executeWrite(status ->
                store.claimBatch(now, firstLease, now.plusSeconds(30), 10));
        var competingClaim = transactions.executeWrite(status ->
                store.claimBatch(now.plusSeconds(1), secondLease, now.plusSeconds(31), 10));
        var recoveredClaim = transactions.executeWrite(status ->
                store.claimBatch(now.plusSeconds(31), secondLease, now.plusSeconds(61), 10));

        assertEquals(1, firstClaim.size());
        assertTrue(competingClaim.isEmpty());
        assertEquals(1, recoveredClaim.size());
        assertEquals(firstClaim.getFirst().eventId(), recoveredClaim.getFirst().eventId());
        assertEquals(2, recoveredClaim.getFirst().publicationAttempts());
        String eventId = recoveredClaim.getFirst().eventId();
        assertThrows(AnalysisOutboxPersistenceException.class, () -> transactions.executeWrite(status -> {
            store.renewLease(eventId, firstLease, now.plusSeconds(32), now.plusSeconds(90));
            return null;
        }));
        transactions.executeWrite(status -> {
            store.renewLease(eventId, secondLease, now.plusSeconds(32), now.plusSeconds(90));
            return null;
        });
    }

    /** Reject renewal after lease expiry even when no successor has claimed the row yet. */
    @Test
    void shouldRejectOutboxLeaseRenewalAfterExpiryBeforeSuccessorClaim() {
        AnalysisOutbox transactionalOutbox = context.getBean(AnalysisOutbox.class);
        RawItemProcessingService service = service(matchingAnalyzer(), transactionalOutbox);
        service.process(rawItem(RAW_ITEM_1, SOURCE_EVENT_1, "Java Kafka"));
        AnalysisOutboxStore store = context.getBean(AnalysisOutboxStore.class);
        Instant now = Instant.parse("2026-09-15T12:05:00Z");
        Instant expiredAt = now.plusSeconds(30);
        Instant recoveryAt = expiredAt.plusSeconds(1);
        UUID expiredLease = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID recoveryLease = UUID.fromString("55555555-5555-5555-5555-555555555555");

        var initialClaim = transactions.executeWrite(status ->
                store.claimBatch(now, expiredLease, expiredAt, 10));
        String eventId = initialClaim.getFirst().eventId();

        assertThrows(AnalysisOutboxPersistenceException.class, () -> transactions.executeWrite(status -> {
            store.renewLease(eventId, expiredLease, recoveryAt, recoveryAt.plusSeconds(30));
            return null;
        }));

        var recoveredClaim = transactions.executeWrite(status ->
                store.claimBatch(recoveryAt, recoveryLease, recoveryAt.plusSeconds(30), 10));

        assertEquals(1, recoveredClaim.size());
        assertEquals(eventId, recoveredClaim.getFirst().eventId());
        assertEquals(2, recoveredClaim.getFirst().publicationAttempts());
    }

    /** Reject retry metadata after lease expiry even when no successor has claimed the row yet. */
    @Test
    void shouldRejectOutboxFailureMetadataAfterExpiryBeforeSuccessorClaim() throws Exception {
        AnalysisOutbox transactionalOutbox = context.getBean(AnalysisOutbox.class);
        RawItemProcessingService service = service(matchingAnalyzer(), transactionalOutbox);
        service.process(rawItem(RAW_ITEM_1, SOURCE_EVENT_1, "Java Kafka"));
        AnalysisOutboxStore store = context.getBean(AnalysisOutboxStore.class);
        Instant now = Instant.parse("2026-09-15T12:05:00Z");
        Instant expiredAt = now.plusSeconds(30);
        Instant failureAt = expiredAt.plusSeconds(1);
        UUID expiredLease = UUID.fromString("66666666-6666-6666-6666-666666666666");
        UUID recoveryLease = UUID.fromString("77777777-7777-7777-7777-777777777777");

        var initialClaim = transactions.executeWrite(status ->
                store.claimBatch(now, expiredLease, expiredAt, 10));
        String eventId = initialClaim.getFirst().eventId();

        assertThrows(AnalysisOutboxPersistenceException.class, () -> transactions.executeWrite(status -> {
            store.markFailed(
                    eventId,
                    expiredLease,
                    failureAt,
                    failureAt.plusSeconds(2),
                    "broker timeout after lease expiry");
            return null;
        }));
        assertEquals(0, outboxFailureCount());

        var recoveredClaim = transactions.executeWrite(status ->
                store.claimBatch(failureAt, recoveryLease, failureAt.plusSeconds(30), 10));

        assertEquals(1, recoveredClaim.size());
        assertEquals(eventId, recoveredClaim.getFirst().eventId());
        assertEquals(2, recoveredClaim.getFirst().publicationAttempts());
    }

    /** Refresh a later batch entry lease immediately before send so queueing time cannot expire ownership. */
    @Test
    void shouldRenewOutboxLeaseBeforePublishingLaterBatchEntry() throws Exception {
        AnalysisOutbox transactionalOutbox = context.getBean(AnalysisOutbox.class);
        RawItemProcessingService service = service(matchingAnalyzer(), transactionalOutbox);
        service.process(rawItem(RAW_ITEM_1, SOURCE_EVENT_1, "Java Kafka"));
        service.process(rawItem(RAW_ITEM_2, SOURCE_EVENT_2, "Java Kafka"));
        assertEquals(2, outboxRowCount());

        AnalysisOutboxStore store = context.getBean(AnalysisOutboxStore.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-09-15T12:05:00Z"));
        AtomicInteger sends = new AtomicInteger();
        AtomicReference<List<AnalysisOutboxEntry>> competingClaim = new AtomicReference<>(List.of());
        AnalysisKafkaClient client = (topic, key, payload) -> {
            int sendNumber = sends.incrementAndGet();
            if (sendNumber == 1) {
                clock.advance(Duration.ofSeconds(20));
                return;
            }
            clock.advance(Duration.ofSeconds(15));
            UUID competingLease = UUID.fromString("33333333-3333-3333-3333-333333333333");
            competingClaim.set(transactions.executeWrite(status -> store.claimBatch(
                    clock.instant(),
                    competingLease,
                    clock.instant().plusSeconds(30),
                    10)));
        };

        dispatcher(store, client, clock).dispatchAvailable();

        assertEquals(2, sends.get());
        assertTrue(competingClaim.get().isEmpty());
        assertEquals(2, outboxPublishedCount());
    }

    /** Skip a later batch entry after its original lease expires before pre-publication renewal. */
    @Test
    void shouldSkipExpiredLaterBatchEntryAndAllowSubsequentRecovery() throws Exception {
        AnalysisOutbox transactionalOutbox = context.getBean(AnalysisOutbox.class);
        RawItemProcessingService service = service(matchingAnalyzer(), transactionalOutbox);
        service.process(rawItem(RAW_ITEM_1, SOURCE_EVENT_1, "Java Kafka"));
        service.process(rawItem(RAW_ITEM_2, SOURCE_EVENT_2, "Java Kafka"));
        MutableClock clock = new MutableClock(Instant.parse("2026-09-15T12:05:00Z"));
        AtomicInteger staleSends = new AtomicInteger();
        AnalysisKafkaClient staleClient = (topic, key, payload) -> {
            if (staleSends.incrementAndGet() == 1) {
                clock.advance(Duration.ofSeconds(31));
            }
        };

        dispatcher(context.getBean(AnalysisOutboxStore.class), staleClient, clock).dispatchAvailable();

        assertEquals(1, staleSends.get());
        assertEquals(1, outboxPublishedCount());

        AtomicInteger recoverySends = new AtomicInteger();
        AnalysisKafkaClient recoveryClient = (topic, key, payload) -> recoverySends.incrementAndGet();
        dispatcher(context.getBean(AnalysisOutboxStore.class), recoveryClient, clock).dispatchAvailable();

        assertEquals(1, recoverySends.get());
        assertEquals(2, outboxPublishedCount());
    }

    /** Publish a committed outbox row and mark it complete without changing its serialized bytes. */
    @Test
    void shouldDispatchCommittedOutboxEvent() throws Exception {
        AnalysisOutbox transactionalOutbox = context.getBean(AnalysisOutbox.class);
        RawItemProcessingService service = service(matchingAnalyzer(), transactionalOutbox);
        RawItemProcessingResult result = service.process(rawItem(RAW_ITEM_1, SOURCE_EVENT_1, "Java Kafka"));
        AtomicReference<byte[]> sentPayload = new AtomicReference<>();
        AnalysisKafkaClient client = (topic, key, payload) -> sentPayload.set(payload.clone());

        dispatcher(client, Instant.parse("2026-09-15T12:05:00Z")).dispatchAvailable();

        assertTrue(sentPayload.get().length > 0);
        assertEquals(result.eventId(), outboxEventId());
        assertTrue(outboxPublished());
        assertEquals(1, outboxPublicationAttempts());
    }

    /** Keep a failed Kafka publication pending with retry metadata instead of losing the outbox row. */
    @Test
    void shouldKeepFailedOutboxPublicationPending() throws Exception {
        AnalysisOutbox transactionalOutbox = context.getBean(AnalysisOutbox.class);
        RawItemProcessingService service = service(matchingAnalyzer(), transactionalOutbox);
        service.process(rawItem(RAW_ITEM_1, SOURCE_EVENT_1, "Java Kafka"));
        AnalysisKafkaClient client = (topic, key, payload) -> {
            throw new IllegalStateException("broker unavailable");
        };

        dispatcher(client, Instant.parse("2026-09-15T12:05:00Z")).dispatchAvailable();

        assertFalse(outboxPublished());
        assertEquals(1, outboxPublicationAttempts());
        assertTrue(outboxLastError().contains("broker unavailable"));

        AtomicReference<byte[]> retriedPayload = new AtomicReference<>();
        AnalysisKafkaClient recoveredClient = (topic, key, payload) -> retriedPayload.set(payload.clone());
        dispatcher(recoveredClient, Instant.parse("2026-09-15T12:05:03Z")).dispatchAvailable();

        assertTrue(outboxPublished());
        assertEquals(2, outboxPublicationAttempts());
        assertTrue(retriedPayload.get().length > 0);
    }

    /** Leave an expired failed publication immediately reclaimable instead of applying stale retry backoff. */
    @Test
    void shouldLeaveExpiredFailedPublicationImmediatelyReclaimable() throws Exception {
        AnalysisOutbox transactionalOutbox = context.getBean(AnalysisOutbox.class);
        RawItemProcessingService service = service(matchingAnalyzer(), transactionalOutbox);
        service.process(rawItem(RAW_ITEM_1, SOURCE_EVENT_1, "Java Kafka"));
        MutableClock clock = new MutableClock(Instant.parse("2026-09-15T12:05:00Z"));
        AtomicInteger staleSends = new AtomicInteger();
        AnalysisKafkaClient staleClient = (topic, key, payload) -> {
            staleSends.incrementAndGet();
            clock.advance(Duration.ofSeconds(31));
            throw new IllegalStateException("broker timeout after lease expiry");
        };

        dispatcher(context.getBean(AnalysisOutboxStore.class), staleClient, clock).dispatchAvailable();

        assertEquals(1, staleSends.get());
        assertFalse(outboxPublished());
        assertEquals(1, outboxPublicationAttempts());
        assertEquals(0, outboxFailureCount());

        AtomicInteger recoverySends = new AtomicInteger();
        AnalysisKafkaClient recoveryClient = (topic, key, payload) -> recoverySends.incrementAndGet();
        dispatcher(context.getBean(AnalysisOutboxStore.class), recoveryClient, clock).dispatchAvailable();

        assertEquals(1, recoverySends.get());
        assertTrue(outboxPublished());
        assertEquals(2, outboxPublicationAttempts());
    }

    /** Stop the current outbox batch when lifecycle interruption escapes synchronous Kafka publication. */
    @Test
    void shouldStopOutboxBatchWhenPublicationIsInterrupted() throws Exception {
        AnalysisOutbox transactionalOutbox = context.getBean(AnalysisOutbox.class);
        RawItemProcessingService service = service(matchingAnalyzer(), transactionalOutbox);
        service.process(rawItem(RAW_ITEM_1, SOURCE_EVENT_1, "Java Kafka"));
        service.process(rawItem(RAW_ITEM_2, SOURCE_EVENT_2, "Java Kafka"));
        AtomicInteger sends = new AtomicInteger();
        AnalysisKafkaClient client = (topic, key, payload) -> {
            sends.incrementAndGet();
            throw new IllegalStateException(
                    "Kafka acknowledgement wait interrupted",
                    new InterruptedException("application shutdown"));
        };

        try {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> dispatcher(client, Instant.parse("2026-09-15T12:05:00Z")).dispatchAvailable());

            assertTrue(failure.getMessage().contains("Interrupted while publishing Analysis outbox event"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, sends.get());
            assertEquals(0, outboxPublishedCount());
            assertEquals(0, outboxFailureCount());
        } finally {
            Thread.interrupted();
        }
    }

    /** Republish identical event bytes when Kafka acknowledges before the published marker can be persisted. */
    @Test
    void shouldRepublishSameBytesAfterPublishedMarkerFailure() throws Exception {
        AnalysisOutbox transactionalOutbox = context.getBean(AnalysisOutbox.class);
        RawItemProcessingService service = service(matchingAnalyzer(), transactionalOutbox);
        RawItemProcessingResult result = service.process(rawItem(RAW_ITEM_1, SOURCE_EVENT_1, "Java Kafka"));
        AnalysisOutboxStore delegate = context.getBean(AnalysisOutboxStore.class);
        AnalysisOutboxStore failingStore = new FailingFirstPublishedMarkerStore(delegate);
        List<SentRecord> sentRecords = new ArrayList<>();
        AnalysisKafkaClient client = (topic, key, payload) -> sentRecords.add(new SentRecord(topic, key, payload.clone()));

        dispatcher(failingStore, client, Instant.parse("2026-09-15T12:05:00Z")).dispatchAvailable();

        assertFalse(outboxPublished());
        assertEquals(1, outboxPublicationAttempts());
        assertEquals(1, sentRecords.size());

        dispatcher(failingStore, client, Instant.parse("2026-09-15T12:05:03Z")).dispatchAvailable();

        assertTrue(outboxPublished());
        assertEquals(2, outboxPublicationAttempts());
        assertEquals(2, sentRecords.size());
        SentRecord first = sentRecords.get(0);
        SentRecord second = sentRecords.get(1);
        assertEquals(first.topic(), second.topic());
        assertEquals(first.key(), second.key());
        assertArrayEquals(first.payload(), second.payload());
        assertEquals(result.eventId(), ItemAnalyzed.parseFrom(first.payload()).getEnvelope().getEventId());
        assertEquals(result.eventId(), ItemAnalyzed.parseFrom(second.payload()).getEnvelope().getEventId());
        assertEquals(result.eventId(), outboxEventId());
    }

    /** Roll back a new deduplication claim when analyzed outbox staging fails. */
    @Test
    void shouldRollbackNewClaimWhenAnalyzedOutboxAppendFails() {
        RecordingOutbox outbox = new RecordingOutbox();
        outbox.failAnalyzed();
        RawItemProcessingService service = service(matchingAnalyzer(), outbox);
        DiscoveredRawItem rawItem = rawItem(RAW_ITEM_1, SOURCE_EVENT_1, "Java Kafka");
        String normalizedItemId = normalizer.normalize(rawItem).normalizedItemId();

        assertThrows(AnalysisOutboxPersistenceException.class, () -> service.process(rawItem));

        assertTrue(inspection.find(DEFAULT_PROFILE_ID, normalizedItemId).isEmpty());
    }

    /**
     * Roll back duplicate observation when rejected outbox staging fails.
     */
    @Test
    void shouldRollbackDuplicateObservationWhenRejectedOutboxAppendFails() {
        RecordingOutbox outbox = new RecordingOutbox();
        RawItemProcessingService service = service(matchingAnalyzer(), outbox);
        DiscoveredRawItem first = rawItem(RAW_ITEM_1, SOURCE_EVENT_1, "Java Kafka");
        DiscoveredRawItem duplicate = rawItem(RAW_ITEM_2, SOURCE_EVENT_2, "Java Kafka");
        String normalizedItemId = normalizer.normalize(first).normalizedItemId();

        service.process(first);
        outbox.failRejected();

        assertThrows(AnalysisOutboxPersistenceException.class, () -> service.process(duplicate));

        AnalysisItemInspection persisted = inspection.find(DEFAULT_PROFILE_ID, normalizedItemId).orElseThrow();
        assertEquals(1, persisted.discoveryCount());
        assertEquals(RAW_ITEM_1, persisted.lastRawItemId());
        assertEquals(SOURCE_EVENT_1, persisted.lastSourceEventId());
    }

    private RawItemProcessingService service(ContentAnalyzer analyzer, AnalysisOutbox outbox) {
        return new RawItemProcessingService(
                normalizer,
                repository,
                analyzer,
                outbox,
                transactions,
                new AnalysisObservability(Optional.empty(), Optional.empty()),
                Clock.fixed(PROCESSING_TIME, ZoneOffset.UTC));
    }

    private static ContentAnalyzer matchingAnalyzer() {
        return (item, settings) -> new AnalysisDecision(
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
                new KeywordAnalysisSettings(List.of("java", "kafka"), 1),
                Optional.of("external-job-01"),
                Optional.of("Senior Java Engineer"),
                URI.create("https://example.test/jobs/1"),
                content,
                "text/plain",
                Optional.of(PUBLISHED_AT));
    }

    private AnalysisOutboxDispatcher dispatcher(AnalysisKafkaClient client, Instant now) {
        return dispatcher(context.getBean(AnalysisOutboxStore.class), client, now);
    }

    private AnalysisOutboxDispatcher dispatcher(AnalysisOutboxStore store, AnalysisKafkaClient client, Instant now) {
        return dispatcher(store, client, Clock.fixed(now, ZoneOffset.UTC));
    }

    private AnalysisOutboxDispatcher dispatcher(AnalysisOutboxStore store, AnalysisKafkaClient client, Clock clock) {
        return new AnalysisOutboxDispatcher(
                store,
                client,
                context.getBean(AnalysisOutboxConfiguration.class),
                transactions,
                new AnalysisObservability(Optional.empty(), Optional.empty()),
                clock);
    }

    private int outboxRowCount() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM analysis.event_outbox")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private String outboxEventId() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT event_id FROM analysis.event_outbox")) {
            rows.next();
            return rows.getString(1);
        }
    }

    private String outboxTraceparent() throws Exception {
        return outboxScalar("SELECT traceparent FROM analysis.event_outbox", String.class);
    }

    private boolean outboxPublished() throws Exception {
        return outboxScalar("SELECT published_at IS NOT NULL FROM analysis.event_outbox", Boolean.class);
    }

    private int outboxPublishedCount() throws Exception {
        return outboxScalar(
                        "SELECT COUNT(*) FROM analysis.event_outbox WHERE published_at IS NOT NULL",
                        Long.class)
                .intValue();
    }

    private int outboxPublicationAttempts() throws Exception {
        return outboxScalar("SELECT publication_attempts FROM analysis.event_outbox", Integer.class);
    }

    private int outboxFailureCount() throws Exception {
        return outboxScalar(
                        "SELECT COUNT(*) FROM analysis.event_outbox WHERE last_error IS NOT NULL",
                        Long.class)
                .intValue();
    }

    private String outboxLastError() throws Exception {
        return outboxScalar("SELECT last_error FROM analysis.event_outbox", String.class);
    }

    private <T> T outboxScalar(String sql, Class<T> type) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getObject(1, type);
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

    private record SentRecord(String topic, String key, byte[] payload) {
    }

    private static final class FailingFirstPublishedMarkerStore implements AnalysisOutboxStore {
        private final AnalysisOutboxStore delegate;
        private final AtomicBoolean failNextPublishedMarker = new AtomicBoolean(true);

        private FailingFirstPublishedMarkerStore(AnalysisOutboxStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void append(AnalysisOutboxEntry entry) {
            delegate.append(entry);
        }

        @Override
        public AnalysisOutboxBacklog inspectBacklog() {
            return delegate.inspectBacklog();
        }

        @Override
        public List<AnalysisOutboxEntry> claimBatch(
                Instant now, UUID leaseToken, Instant leaseExpiresAt, int limit) {
            return delegate.claimBatch(now, leaseToken, leaseExpiresAt, limit);
        }

        @Override
        public void renewLease(String eventId, UUID leaseToken, Instant renewedAt, Instant leaseExpiresAt) {
            delegate.renewLease(eventId, leaseToken, renewedAt, leaseExpiresAt);
        }

        @Override
        public void markPublished(String eventId, UUID leaseToken, Instant publishedAt) {
            if (failNextPublishedMarker.compareAndSet(true, false)) {
                throw new AnalysisOutboxPersistenceException(
                        "simulated published-marker failure",
                        new IllegalStateException("database unavailable after Kafka acknowledgement"));
            }
            delegate.markPublished(eventId, leaseToken, publishedAt);
        }

        @Override
        public void markFailed(
                String eventId, UUID leaseToken, Instant failedAt, Instant nextAttemptAt, String failureMessage) {
            delegate.markFailed(eventId, leaseToken, failedAt, nextAttemptAt, failureMessage);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant initial) {
            this.current = initial;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Test clock supports UTC only");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }
    }

    private static final class RecordingOutbox implements AnalysisOutbox {
        private final AtomicReference<AnalyzedItem> analyzed = new AtomicReference<>();
        private final AtomicReference<RejectedItem> rejected = new AtomicReference<>();
        private boolean failAnalyzed;
        private boolean failRejected;

        @Override
        public AnalysisPublicationResult enqueueAnalyzed(AnalyzedItem analyzedItem) {
            if (failAnalyzed) {
                throw publicationFailure(analyzedItem.item().normalizedItemId(), "analyzed-items");
            }
            analyzed.set(analyzedItem);
            return new AnalysisPublicationResult(
                    "analysis-event-01", "analyzed-items", analyzedItem.item().normalizedItemId());
        }

        @Override
        public AnalysisPublicationResult enqueueRejected(RejectedItem rejectedItem) {
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

        private static AnalysisOutboxPersistenceException publicationFailure(String normalizedItemId, String topic) {
            return new AnalysisOutboxPersistenceException(
                    "simulated outbox append failure normalizedItemId=" + normalizedItemId + " topic=" + topic,
                    new IllegalStateException("database unavailable"));
        }
    }
}
