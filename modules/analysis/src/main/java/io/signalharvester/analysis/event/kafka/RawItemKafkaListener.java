package io.signalharvester.analysis.event.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.OffsetReset;
import io.micronaut.configuration.kafka.annotation.OffsetStrategy;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.context.annotation.Requires;
import io.signalharvester.analysis.application.RawItemProcessor;
import io.signalharvester.events.collection.v1.RawItemDiscovered;
import java.util.Map;
import java.util.Objects;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

/**
 * Kafka ingress adapter for version-one collection raw-item events.
 *
 * <p>The listener commits the consumed offset only after the complete application processing path
 * returns successfully, including durable deduplication state and acknowledged terminal publication.</p>
 */
@KafkaListener(
        value = "${signalharvester.analysis.consumer-group:signalharvester-analysis-v1}",
        offsetReset = OffsetReset.EARLIEST,
        offsetStrategy = OffsetStrategy.DISABLED)
@Requires(property = "signalharvester.analysis.enabled", notEquals = "false", defaultValue = "true")
public class RawItemKafkaListener {

    private final RawItemDiscoveredMapper mapper;
    private final RawItemProcessor processor;

    public RawItemKafkaListener(RawItemDiscoveredMapper mapper, RawItemProcessor processor) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.processor = Objects.requireNonNull(processor, "processor");
    }

    /**
     * Decodes and processes one raw item, then synchronously commits its Kafka offset.
     */
    @Topic("${signalharvester.kafka.raw-item-discovered-topic:signalharvester.collection.raw-item-discovered.v1}")
    public void receive(
            @KafkaKey String key,
            byte[] payload,
            long offset,
            int partition,
            String topic,
            Consumer<?, ?> consumer) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(consumer, "consumer");
        RawItemDiscovered event = parse(payload);
        if (!key.equals(event.getRawItemId())) {
            throw new IllegalArgumentException("Kafka key must match RawItemDiscovered.raw_item_id");
        }
        processor.process(mapper.map(event));
        consumer.commitSync(Map.of(
                new TopicPartition(topic, partition),
                new OffsetAndMetadata(offset + 1)));
    }

    private static RawItemDiscovered parse(byte[] payload) {
        try {
            return RawItemDiscovered.parseFrom(payload);
        } catch (InvalidProtocolBufferException exception) {
            throw new IllegalArgumentException("Invalid RawItemDiscovered Protobuf payload", exception);
        }
    }
}
