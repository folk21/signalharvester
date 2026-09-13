package io.signalharvester.collection.event.kafka;

import io.signalharvester.collection.configuration.CollectionKafkaConfiguration;
import io.signalharvester.collection.event.RawItemEventPublisher;
import io.signalharvester.collection.event.RawItemPublicationContext;
import io.signalharvester.collection.event.RawItemPublicationException;
import io.signalharvester.collection.event.RawItemPublicationResult;
import io.signalharvester.collection.source.ExtractedSourceItem;
import io.signalharvester.events.collection.v1.RawItemDiscovered;
import jakarta.inject.Singleton;
import java.util.Objects;

/** Publishes collection raw-item integration events to Kafka as explicit Protobuf bytes. */
@Singleton
public final class KafkaRawItemEventPublisher implements RawItemEventPublisher {

    private final CollectionRawItemKafkaClient kafkaClient;
    private final CollectionKafkaConfiguration configuration;
    private final RawItemDiscoveredMapper mapper;

    public KafkaRawItemEventPublisher(
            CollectionRawItemKafkaClient kafkaClient,
            CollectionKafkaConfiguration configuration,
            RawItemDiscoveredMapper mapper) {
        this.kafkaClient = Objects.requireNonNull(kafkaClient, "kafkaClient");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Maps and serializes the event locally, then uses a blocking acknowledged Kafka send. */
    @Override
    public RawItemPublicationResult publish(
            ExtractedSourceItem item,
            RawItemPublicationContext context) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(context, "context");

        String topic = configuration.getRawItemDiscoveredTopic();
        try {
            RawItemDiscovered event = mapper.map(item, context);
            kafkaClient.send(topic, event.getRawItemId(), event.toByteArray());
            return new RawItemPublicationResult(
                    event.getEnvelope().getEventId(),
                    event.getRawItemId(),
                    topic);
        } catch (RuntimeException failure) {
            throw new RawItemPublicationException(
                    context.rawItemId(),
                    context.correlationId(),
                    topic,
                    "Failed to publish RawItemDiscovered event",
                    failure);
        }
    }
}
