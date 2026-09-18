package io.signalharvester.collection.event.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.signalharvester.collection.configuration.CollectionKafkaConfiguration;
import io.signalharvester.collection.event.RawItemPublicationContext;
import io.signalharvester.collection.event.RawItemPublicationException;
import io.signalharvester.collection.event.RawItemPublicationResult;
import io.signalharvester.collection.source.ExtractedSourceItem;
import io.signalharvester.configuration.api.MonitoringProfileAnalysisSettings;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.events.collection.v1.RawItemDiscovered;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link KafkaRawItemEventPublisher} topic/key selection, Protobuf publication, and contextual
 * failure normalization for collection-owned extracted items.
 *
 * <p>Related specifications: {@code backend-rss-atom-extraction}, {@code backend-event-contracts}.</p>
 *
 * <p>Features: {@code EVENTING.PIPELINE}, {@code CONTRACTS.KAFKA_PROTOBUF}.</p>
 */
class KafkaRawItemEventPublisherTest {

    private static final String RAW_ITEM_TOPIC = "raw-items";
    private static final String RAW_ITEM_ID = "raw-1";
    private static final String RUN_ID = "run-1";
    private static final String PROFILE_ID = "profile-1";
    private static final String BROKER_FAILURE_MESSAGE = "broker unavailable";
    private static final URI ITEM_URI = URI.create("https://example.test/jobs/42");
    private static final Instant DISCOVERED_AT = Instant.parse("2026-09-10T13:00:00Z");
    private static final SourceId SOURCE_ID = SourceId.of(
            UUID.fromString("00000000-0000-0000-0000-000000000301"));

    /**
     * Serialize Protobuf and use raw item id as Kafka key.
     */
    @Test
    void shouldSerializeProtobufAndUseRawItemIdAsKafkaKey() throws Exception {
        AtomicReference<String> topic = new AtomicReference<>();
        AtomicReference<String> key = new AtomicReference<>();
        AtomicReference<byte[]> payload = new AtomicReference<>();
        CollectionRawItemKafkaClient client = (sentTopic, sentKey, sentPayload) -> {
            topic.set(sentTopic);
            key.set(sentKey);
            payload.set(sentPayload);
        };
        CollectionKafkaConfiguration configuration = () -> "signalharvester.collection.raw-item-discovered.v1";
        KafkaRawItemEventPublisher publisher = new KafkaRawItemEventPublisher(
                client, configuration, new RawItemDiscoveredMapper(UUID::randomUUID));

        RawItemPublicationResult result = publisher.publish(item(), publicationContext());
        RawItemDiscovered decoded = RawItemDiscovered.parseFrom(payload.get());

        assertEquals(configuration.getRawItemDiscoveredTopic(), topic.get());
        assertEquals(decoded.getRawItemId(), key.get());
        assertEquals(decoded.getEnvelope().getEventId(), result.eventId());
        assertEquals(decoded.getRawItemId(), result.rawItemId());
        assertEquals(topic.get(), result.topic());
        assertEquals(RUN_ID, decoded.getEnvelope().getCorrelationId());
    }

    /**
     * Normalize Kafka failure to collection publication exception.
     */
    @Test
    void shouldNormalizeKafkaFailureToCollectionPublicationException() {
        CollectionRawItemKafkaClient client = (topic, key, payload) -> {
            throw new IllegalStateException(BROKER_FAILURE_MESSAGE);
        };
        KafkaRawItemEventPublisher publisher = new KafkaRawItemEventPublisher(
                client,
                () -> RAW_ITEM_TOPIC,
                new RawItemDiscoveredMapper(UUID::randomUUID));

        RawItemPublicationException failure = assertThrows(
                RawItemPublicationException.class,
                () -> publisher.publish(item(), publicationContext()));

        assertEquals(RAW_ITEM_ID, failure.rawItemId());
        assertEquals(RUN_ID, failure.correlationId());
        assertEquals(RAW_ITEM_TOPIC, failure.topic());
        assertEquals(BROKER_FAILURE_MESSAGE, failure.getCause().getMessage());
    }

    /**
     * Normalize mapper failure to publication exception.
     */
    @Test
    void shouldNormalizeMapperFailureToPublicationException() {
        KafkaRawItemEventPublisher publisher = new KafkaRawItemEventPublisher(
                (topic, key, payload) -> {
                    throw new AssertionError("Kafka must not be called when mapping fails");
                },
                () -> RAW_ITEM_TOPIC,
                new RawItemDiscoveredMapper(() -> null));

        RawItemPublicationException failure = assertThrows(
                RawItemPublicationException.class,
                () -> publisher.publish(item(), publicationContext()));

        assertEquals(RAW_ITEM_ID, failure.rawItemId());
        assertEquals(RUN_ID, failure.correlationId());
        assertEquals(RAW_ITEM_TOPIC, failure.topic());
        assertEquals(NullPointerException.class, failure.getCause().getClass());
    }

    private static ExtractedSourceItem item() {
        return new ExtractedSourceItem(
                SOURCE_ID,
                ITEM_URI,
                Optional.of("external-42"),
                Optional.of("Java Developer"),
                "Java Kafka PostgreSQL",
                "text/plain; charset=UTF-8",
                Optional.empty(),
                DISCOVERED_AT,
                "identity".getBytes(StandardCharsets.UTF_8));
    }

    private static RawItemPublicationContext publicationContext() {
        return new RawItemPublicationContext(
                RAW_ITEM_ID,
                RUN_ID,
                PROFILE_ID,
                "JOB",
                new MonitoringProfileAnalysisSettings(List.of("java", "kafka"), 1),
                Optional.empty());
    }
}
