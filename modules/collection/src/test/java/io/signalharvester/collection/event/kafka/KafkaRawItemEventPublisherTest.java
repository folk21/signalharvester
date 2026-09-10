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

class KafkaRawItemEventPublisherTest {

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
        assertEquals("run-1", decoded.getEnvelope().getCorrelationId());
    }

    @Test
    void shouldNormalizeKafkaFailureToCollectionPublicationException() {
        CollectionRawItemKafkaClient client = (topic, key, payload) -> {
            throw new IllegalStateException("broker unavailable");
        };
        KafkaRawItemEventPublisher publisher = new KafkaRawItemEventPublisher(
                client,
                () -> "raw-items",
                new RawItemDiscoveredMapper(UUID::randomUUID));

        RawItemPublicationException failure = assertThrows(
                RawItemPublicationException.class,
                () -> publisher.publish(content(), publicationContext()));

        assertEquals("raw-1", failure.rawItemId());
        assertEquals("run-1", failure.correlationId());
        assertEquals("raw-items", failure.topic());
        assertEquals("broker unavailable", failure.getCause().getMessage());
    }

    @Test
    void shouldNormalizeMappingFailureToPublicationException() {
        FetchedSourceContent invalidCharsetContent = new FetchedSourceContent(
                SourceId.of(UUID.fromString("00000000-0000-0000-0000-000000000302")),
                URI.create("https://example.test/jobs"),
                200,
                Optional.of("text/plain; charset=not-a-real-charset"),
                "payload".getBytes(StandardCharsets.UTF_8),
                Instant.parse("2026-09-10T13:00:00Z"));
        KafkaRawItemEventPublisher publisher = new KafkaRawItemEventPublisher(
                (topic, key, payload) -> {
                    throw new AssertionError("Kafka must not be called when mapping fails");
                },
                () -> "raw-items",
                new RawItemDiscoveredMapper(UUID::randomUUID));

        RawItemPublicationException failure = assertThrows(
                RawItemPublicationException.class,
                () -> publisher.publish(invalidCharsetContent, publicationContext()));

        assertEquals("raw-1", failure.rawItemId());
        assertEquals("run-1", failure.correlationId());
        assertEquals("raw-items", failure.topic());
        assertEquals(java.nio.charset.UnsupportedCharsetException.class, failure.getCause().getClass());
    }

    private static FetchedSourceContent content() {
        return new FetchedSourceContent(
                SourceId.of(UUID.fromString("00000000-0000-0000-0000-000000000301")),
                URI.create("https://example.test/jobs"),
                200,
                Optional.of("application/json; charset=UTF-8"),
                "{\"title\":\"Java Developer\"}".getBytes(StandardCharsets.UTF_8),
                Instant.parse("2026-09-10T13:00:00Z"));
    }

    private static RawItemPublicationContext publicationContext() {
        return new RawItemPublicationContext("raw-1", "run-1", "profile-1", "JOB", Optional.empty());
    }
}
