package io.signalharvester.eventobservation.event.kafka;

import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.Topic;

/** Micronaut Kafka client for acknowledged Event Observation dead-letter publication. */
@KafkaClient("event-observation-dead-letter")
public interface EventObservationDeadLetterKafkaClient {

    /** Publishes one serialized dead-letter event and waits for broker acknowledgement. */
    void send(@Topic String topic, @KafkaKey String key, byte[] payload);
}
