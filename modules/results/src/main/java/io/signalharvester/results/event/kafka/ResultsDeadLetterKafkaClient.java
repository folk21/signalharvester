package io.signalharvester.results.event.kafka;

import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.Topic;

/** Micronaut Kafka client for acknowledged Results dead-letter publication. */
@KafkaClient("results-dead-letter")
public interface ResultsDeadLetterKafkaClient {

    /** Publishes one serialized dead-letter event and waits for broker acknowledgement. */
    void send(@Topic String topic, @KafkaKey String key, byte[] payload);
}
