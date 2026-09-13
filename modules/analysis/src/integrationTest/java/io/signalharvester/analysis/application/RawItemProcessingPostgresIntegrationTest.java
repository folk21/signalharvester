package io.signalharvester.analysis.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Timestamp;
import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.analysis.api.AnalysisItemInspection;
import io.signalharvester.analysis.api.AnalysisItemInspectionQuery;
import io.signalharvester.analysis.event.AnalysisEventPublisher;
import io.signalharvester.analysis.event.AnalysisPublicationException;
import io.signalharvester.analysis.event.AnalysisPublicationResult;
import io.signalharvester.analysis.event.AnalyzedItem;
import io.signalharvester.analysis.event.RejectedItem;
import io.signalharvester.analysis.event.kafka.RawItemDiscoveredMapper;
import io.signalharvester.analysis.event.kafka.RawItemKafkaListener;
import io.signalharvester.analysis.model.DiscoveredRawItem;
import io.signalharvester.analysis.normalization.ContentNormalizer;
import io.signalharvester.analysis.persistence.DeduplicationClaimRepository;
import io.signalharvester.events.collection.v1.RawItemDiscovered;
import io.signalharvester.events.common.v1.EventEnvelope;
import java.lang.reflect.Proxy;
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
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class RawItemProcessingPostgresIntegrationTest {

    private static final Instant PROCESSING_TIME = Instant.parse("2026-09-13T08:00:00Z");

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

    @Test
    void shouldPublishAnalyzedForValidIrrelevantItemAndPersistClaim() {
        RecordingPublisher publisher = new RecordingPublisher();
        ContentAnalyzer analyzer = item -> new AnalysisDecision(
                false,
                "NO_KEYWORD_MATCH",
                0,
                List.of(),
                "No configured keywords matched",
                "test-analyzer");
        RawItemProcessingService service = service(analyzer, publisher);
        DiscoveredRawItem rawItem = rawItem("raw-01", "source-event-01", "Unrelated content");

        RawItemProcessingResult result = service.process(rawItem);

        assertEquals(RawItemProcessingStatus.ANALYZED, result.status());
        assertTrue(publisher.analyzed().isPresent());
        assertFalse(publisher.analyzed().orElseThrow().decision().relevant());
        assertTrue(publisher.rejected().isEmpty());

        String normalizedItemId = normalizer.normalize(rawItem).normalizedItemId();
        AnalysisItemInspection persisted = inspection.find("profile-01", normalizedItemId).orElseThrow();
        assertEquals(1, persisted.discoveryCount());
        assertEquals("raw-01", persisted.lastRawItemId());
    }

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
                    "test-analyzer");
        };
        RecordingPublisher publisher = new RecordingPublisher();
        RawItemProcessingService service = service(analyzer, publisher);
        DiscoveredRawItem first = rawItem("raw-01", "source-event-01", "Java Kafka");
        DiscoveredRawItem duplicate = rawItem("raw-02", "source-event-02", "Java   Kafka");

        RawItemProcessingResult firstResult = service.process(first);
        RawItemProcessingResult duplicateResult = service.process(duplicate);

        assertEquals(RawItemProcessingStatus.ANALYZED, firstResult.status());
        assertEquals(RawItemProcessingStatus.DUPLICATE, duplicateResult.status());
        assertEquals(1, analyzerCalls.get());
        assertTrue(publisher.rejected().isPresent());
        assertEquals("DUPLICATE", publisher.rejected().orElseThrow().reasonCode());

        String normalizedItemId = normalizer.normalize(first).normalizedItemId();
        AnalysisItemInspection persisted = inspection.find("profile-01", normalizedItemId).orElseThrow();
        assertEquals(2, persisted.discoveryCount());
        assertEquals("raw-02", persisted.lastRawItemId());
        assertEquals("source-event-02", persisted.lastSourceEventId());
    }

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
                    "test-analyzer");
        };
        RecordingPublisher publisher = new RecordingPublisher();
        RawItemProcessingService service = service(analyzer, publisher);
        DiscoveredRawItem firstProfile = rawItem("profile-a", "raw-01", "source-event-01", "Java Kafka");
        DiscoveredRawItem secondProfile = rawItem("profile-b", "raw-02", "source-event-02", "Java Kafka");

        RawItemProcessingResult firstResult = service.process(firstProfile);
        RawItemProcessingResult secondResult = service.process(secondProfile);

        assertEquals(RawItemProcessingStatus.ANALYZED, firstResult.status());
        assertEquals(RawItemProcessingStatus.ANALYZED, secondResult.status());
        assertEquals(firstResult.normalizedItemId(), secondResult.normalizedItemId());
        assertEquals(2, analyzerCalls.get());
        assertTrue(inspection.find("profile-a", firstResult.normalizedItemId()).isPresent());
        assertTrue(inspection.find("profile-b", secondResult.normalizedItemId()).isPresent());
    }

    @Test
    void shouldRollbackNewClaimAndLeaveInputOffsetUncommittedWhenAnalyzedPublicationFails() {
        RecordingPublisher publisher = new RecordingPublisher();
        publisher.failAnalyzed();
        RawItemProcessingService service = service(matchingAnalyzer(), publisher);
        RawItemKafkaListener listener = new RawItemKafkaListener(new RawItemDiscoveredMapper(), service);
        DiscoveredRawItem rawItem = rawItem("raw-01", "source-event-01", "Java Kafka");
        RawItemDiscovered event = event(rawItem);
        String normalizedItemId = normalizer.normalize(rawItem).normalizedItemId();
        AtomicReference<Map<TopicPartition, OffsetAndMetadata>> committed = new AtomicReference<>();

        assertThrows(AnalysisPublicationException.class, () -> listener.receive(
                rawItem.rawItemId(),
                event.toByteArray(),
                9L,
                0,
                "raw-items",
                consumer(committed)));

        assertTrue(inspection.find("profile-01", normalizedItemId).isEmpty());
        assertNull(committed.get());
    }

    @Test
    void shouldRollbackDuplicateObservationWhenRejectedPublicationFails() {
        RecordingPublisher publisher = new RecordingPublisher();
        RawItemProcessingService service = service(matchingAnalyzer(), publisher);
        DiscoveredRawItem first = rawItem("raw-01", "source-event-01", "Java Kafka");
        DiscoveredRawItem duplicate = rawItem("raw-02", "source-event-02", "Java Kafka");
        String normalizedItemId = normalizer.normalize(first).normalizedItemId();

        service.process(first);
        publisher.failRejected();

        assertThrows(AnalysisPublicationException.class, () -> service.process(duplicate));

        AnalysisItemInspection persisted = inspection.find("profile-01", normalizedItemId).orElseThrow();
        assertEquals(1, persisted.discoveryCount());
        assertEquals("raw-01", persisted.lastRawItemId());
        assertEquals("source-event-01", persisted.lastSourceEventId());
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
                "test-analyzer");
    }

    private static RawItemDiscovered event(DiscoveredRawItem rawItem) {
        RawItemDiscovered.Builder builder = RawItemDiscovered.newBuilder()
                .setEnvelope(EventEnvelope.newBuilder()
                        .setEventId(rawItem.sourceEventId())
                        .setEventType("collection.raw-item-discovered.v1")
                        .setOccurredAt(timestamp(rawItem.discoveredAt()))
                        .setCorrelationId(rawItem.correlationId())
                        .setTraceparent(rawItem.traceparent().orElse(""))
                        .setProducer("collection")
                        .setSchemaVersion("v1")
                        .build())
                .setRawItemId(rawItem.rawItemId())
                .setSourceId(rawItem.sourceId())
                .setMonitoringProfileId(rawItem.monitoringProfileId())
                .setInformationCategory(rawItem.informationCategory())
                .setUrl(rawItem.url().toString())
                .setContent(rawItem.content())
                .setContentType(rawItem.contentType());
        rawItem.externalId().ifPresent(builder::setExternalId);
        rawItem.title().ifPresent(builder::setTitle);
        rawItem.publishedAt().map(RawItemProcessingPostgresIntegrationTest::timestamp).ifPresent(builder::setPublishedAt);
        return builder.build();
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Consumer<?, ?> consumer(AtomicReference<Map<TopicPartition, OffsetAndMetadata>> committed) {
        return (Consumer<?, ?>) Proxy.newProxyInstance(
                RawItemProcessingPostgresIntegrationTest.class.getClassLoader(),
                new Class<?>[] {Consumer.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("commitSync") && args != null && args.length == 1) {
                        committed.set((Map<TopicPartition, OffsetAndMetadata>) args[0]);
                        return null;
                    }
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "RecordingKafkaConsumer";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static DiscoveredRawItem rawItem(String rawItemId, String sourceEventId, String content) {
        return rawItem("profile-01", rawItemId, sourceEventId, content);
    }

    private static DiscoveredRawItem rawItem(
            String profileId, String rawItemId, String sourceEventId, String content) {
        return new DiscoveredRawItem(
                sourceEventId,
                "run-01",
                Optional.of("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"),
                Instant.parse("2026-09-13T07:59:00Z"),
                rawItemId,
                "source-01",
                profileId,
                "JOB",
                Optional.of("external-job-01"),
                Optional.of("Senior Java Engineer"),
                URI.create("https://example.test/jobs/1"),
                content,
                "text/plain",
                Optional.of(Instant.parse("2026-09-13T07:55:00Z")));
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
