package io.signalharvester.collection.event.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.signalharvester.collection.configuration.CollectionKafkaConfiguration;
import io.signalharvester.collection.event.RawItemPublicationContext;
import io.signalharvester.collection.event.RawItemPublicationException;
import io.signalharvester.collection.event.RawItemPublicationResult;
import io.signalharvester.collection.source.FetchedSourceContent;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.events.collection.v1.RawItemDiscovered;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link KafkaRawItemEventPublisher} and {@link RawItemDiscoveredMapper} topic/key selection,
 * event mapping, acknowledged publication behavior, and contextual failure normalization.
 *
 * <p>Related specifications: {@code backend-collection-run-orchestration}, {@code backend-event-contracts}.</p>
 */
class KafkaRawItemEventPublisherTest {

    private static final String RAW_ITEM_TOPIC = "raw-items";
    private static final String RAW_ITEM_ID = "raw-1";
    private static final String RUN_ID = "run-1";
    private static final String PROFILE_ID = "profile-1";
    private static final String BROKER_FAILURE_MESSAGE = "broker unavailable";
    private static final URI JOBS_URI = URI.create("https://example.test/jobs");
    private static final Instant FETCHED_AT = Instant.parse("2026-09-10T13:00:00Z");
    private static final SourceId SOURCE_ID = SourceId.of(
            UUID.fromString("00000000-0000-0000-0000-000000000301"));
    private static final SourceId INVALID_CHARSET_SOURCE_ID = SourceId.of(
            UUID.fromString("00000000-0000-0000-0000-000000000302"));

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
        RawItemDiscoveredMapper mapper = new RawItemDiscoveredMapper(UUID::randomUUID);
        KafkaRawItemEventPublisher publisher = new KafkaRawItemEventPublisher(client, configuration, mapper);

        RawItemPublicationResult result = publisher.publish(content(), publicationContext());
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
                () -> publisher.publish(content(), publicationContext()));

        assertEquals(RAW_ITEM_ID, failure.rawItemId());
        assertEquals(RUN_ID, failure.correlationId());
        assertEquals(RAW_ITEM_TOPIC, failure.topic());
        assertEquals(BROKER_FAILURE_MESSAGE, failure.getCause().getMessage());
    }

    /**
     * Normalize mapping failure to publication exception.
     */
    @Test
    void shouldNormalizeMappingFailureToPublicationException() {
        FetchedSourceContent invalidCharsetContent = new FetchedSourceContent(
                INVALID_CHARSET_SOURCE_ID,
                JOBS_URI,
                200,
                Optional.of("text/plain; charset=not-a-real-charset"),
                "payload".getBytes(StandardCharsets.UTF_8),
                FETCHED_AT);
        KafkaRawItemEventPublisher publisher = new KafkaRawItemEventPublisher(
                (topic, key, payload) -> {
                    throw new AssertionError("Kafka must not be called when mapping fails");
                },
                () -> RAW_ITEM_TOPIC,
                new RawItemDiscoveredMapper(UUID::randomUUID));

        RawItemPublicationException failure = assertThrows(
                RawItemPublicationException.class,
                () -> publisher.publish(invalidCharsetContent, publicationContext()));

        assertEquals(RAW_ITEM_ID, failure.rawItemId());
        assertEquals(RUN_ID, failure.correlationId());
        assertEquals(RAW_ITEM_TOPIC, failure.topic());
        assertEquals(java.nio.charset.UnsupportedCharsetException.class, failure.getCause().getClass());
    }

    private static FetchedSourceContent content() {
        return new FetchedSourceContent(
                SOURCE_ID,
                JOBS_URI,
                200,
                Optional.of("application/json; charset=UTF-8"),
                "{\"title\":\"Java Developer\"}".getBytes(StandardCharsets.UTF_8),
                FETCHED_AT);
    }

    private static RawItemPublicationContext publicationContext() {
        return new RawItemPublicationContext(RAW_ITEM_ID, RUN_ID, PROFILE_ID, "JOB", Optional.empty());
    }
}
