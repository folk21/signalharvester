package io.signalharvester.collection.event.kafka;

import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.Topic;

/**
 * Micronaut Kafka client used only by the collection raw-item publication adapter.
 */
@KafkaClient("collection-raw-items")
public interface CollectionRawItemKafkaClient {

    /**
     * Sends one already serialized Protobuf event and waits for broker acknowledgement.
     *
     * @param topic configured versioned topic
     * @param key raw-item identifier used for Kafka partitioning
     * @param payload serialized RawItemDiscovered bytes
     */
    void send(@Topic String topic, @KafkaKey String key, byte[] payload);
}
