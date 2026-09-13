package io.signalharvester.results.event.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.OffsetReset;
import io.micronaut.configuration.kafka.annotation.OffsetStrategy;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.context.annotation.Requires;
import io.signalharvester.events.analysis.v1.ItemAnalyzed;
import io.signalharvester.events.analysis.v1.ItemRejected;
import io.signalharvester.results.application.AnalysisOutcomeProjector;
import java.util.Map;
import java.util.Objects;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

/**
 * Kafka ingress adapter that materializes terminal Analysis events into the Results read model.
 * Offsets are committed only after the Results transaction commits successfully.
 */
@KafkaListener(
        value = "${signalharvester.results.consumer-group:signalharvester-results-v1}",
        offsetReset = OffsetReset.EARLIEST,
        offsetStrategy = OffsetStrategy.DISABLED)
@Requires(property = "signalharvester.results.enabled", value = "true")
public class AnalysisOutcomeKafkaListener {

    private final AnalysisOutcomeMapper mapper;
    private final AnalysisOutcomeProjector projector;

    public AnalysisOutcomeKafkaListener(AnalysisOutcomeMapper mapper, AnalysisOutcomeProjector projector) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.projector = Objects.requireNonNull(projector, "projector");
    }

    /**
     * Materializes one analyzed item and commits its Kafka offset after durable persistence.
     */
    @Topic("${signalharvester.kafka.item-analyzed-topic:signalharvester.analysis.item-analyzed.v1}")
    public void receiveAnalyzed(
            @KafkaKey String key,
            byte[] payload,
            long offset,
            int partition,
            String topic,
            Consumer<?, ?> consumer) {
        ItemAnalyzed event = parseAnalyzed(payload);
        requireKey(key, event.getNormalizedItemId(), "ItemAnalyzed.normalized_item_id");
        projector.projectAnalyzed(mapper.map(event));
        commit(consumer, topic, partition, offset);
    }

    /**
     * Materializes one rejected item and commits its Kafka offset after durable persistence.
     */
    @Topic("${signalharvester.kafka.item-rejected-topic:signalharvester.analysis.item-rejected.v1}")
    public void receiveRejected(
            @KafkaKey String key,
            byte[] payload,
            long offset,
            int partition,
            String topic,
            Consumer<?, ?> consumer) {
        ItemRejected event = parseRejected(payload);
        String expectedKey = event.hasNormalizedItemId() ? event.getNormalizedItemId() : event.getRawItemId();
        requireKey(key, expectedKey, "ItemRejected identity");
        projector.projectRejected(mapper.map(event));
        commit(consumer, topic, partition, offset);
    }

    private static ItemAnalyzed parseAnalyzed(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        try {
            return ItemAnalyzed.parseFrom(payload);
        } catch (InvalidProtocolBufferException exception) {
            throw new IllegalArgumentException("Invalid ItemAnalyzed Protobuf payload", exception);
        }
    }

    private static ItemRejected parseRejected(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        try {
            return ItemRejected.parseFrom(payload);
        } catch (InvalidProtocolBufferException exception) {
            throw new IllegalArgumentException("Invalid ItemRejected Protobuf payload", exception);
        }
    }

    private static void requireKey(String actualKey, String expectedKey, String identityDescription) {
        Objects.requireNonNull(actualKey, "key");
        if (!actualKey.equals(expectedKey)) {
            throw new IllegalArgumentException("Kafka key must match " + identityDescription);
        }
    }

    private static void commit(Consumer<?, ?> consumer, String topic, int partition, long offset) {
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(topic, "topic");
        consumer.commitSync(Map.of(
                new TopicPartition(topic, partition),
                new OffsetAndMetadata(offset + 1)));
    }
}
