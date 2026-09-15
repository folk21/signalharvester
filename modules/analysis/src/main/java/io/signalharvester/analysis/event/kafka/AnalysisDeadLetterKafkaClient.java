package io.signalharvester.analysis.event.kafka;

import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.Topic;

/** Micronaut Kafka client for acknowledged Analysis dead-letter publication. */
@KafkaClient("analysis-dead-letter")
public interface AnalysisDeadLetterKafkaClient {

    /** Publishes one serialized dead-letter event and waits for broker acknowledgement. */
    void send(@Topic String topic, @KafkaKey String key, byte[] payload);
}
