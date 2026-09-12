package io.signalharvester.analysis.event.kafka;

import io.signalharvester.analysis.configuration.AnalysisKafkaConfiguration;
import io.signalharvester.analysis.event.AnalysisEventPublisher;
import io.signalharvester.analysis.event.AnalysisPublicationException;
import io.signalharvester.analysis.event.AnalysisPublicationResult;
import io.signalharvester.analysis.event.AnalyzedItem;
import io.signalharvester.analysis.event.RejectedItem;
import io.signalharvester.events.analysis.v1.ItemAnalyzed;
import io.signalharvester.events.analysis.v1.ItemRejected;
import jakarta.inject.Singleton;
import java.util.Objects;

/**
 * Publishes analysis terminal outcomes to versioned Kafka topics as explicit Protobuf bytes.
 */
@Singleton
public final class KafkaAnalysisEventPublisher implements AnalysisEventPublisher {

    private final AnalysisKafkaClient kafkaClient;
    private final AnalysisKafkaConfiguration configuration;
    private final AnalysisEventMapper mapper;

    public KafkaAnalysisEventPublisher(
            AnalysisKafkaClient kafkaClient,
            AnalysisKafkaConfiguration configuration,
            AnalysisEventMapper mapper) {
        this.kafkaClient = Objects.requireNonNull(kafkaClient, "kafkaClient");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public AnalysisPublicationResult publishAnalyzed(AnalyzedItem analyzedItem) {
        Objects.requireNonNull(analyzedItem, "analyzedItem");
        String topic = configuration.getItemAnalyzedTopic();
        String normalizedItemId = analyzedItem.item().normalizedItemId();
        try {
            ItemAnalyzed event = mapper.mapAnalyzed(analyzedItem);
            kafkaClient.send(topic, normalizedItemId, event.toByteArray());
            return new AnalysisPublicationResult(event.getEnvelope().getEventId(), topic, normalizedItemId);
        } catch (RuntimeException failure) {
            throw new AnalysisPublicationException(
                    normalizedItemId, topic, "Failed to publish ItemAnalyzed event", failure);
        }
    }

    @Override
    public AnalysisPublicationResult publishRejected(RejectedItem rejectedItem) {
        Objects.requireNonNull(rejectedItem, "rejectedItem");
        String topic = configuration.getItemRejectedTopic();
        String normalizedItemId = rejectedItem.item().normalizedItemId();
        try {
            ItemRejected event = mapper.mapRejected(rejectedItem);
            kafkaClient.send(topic, normalizedItemId, event.toByteArray());
            return new AnalysisPublicationResult(event.getEnvelope().getEventId(), topic, normalizedItemId);
        } catch (RuntimeException failure) {
            throw new AnalysisPublicationException(
                    normalizedItemId, topic, "Failed to publish ItemRejected event", failure);
        }
    }
}
