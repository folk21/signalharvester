package io.signalharvester.analysis.event.kafka;

import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.Topic;

/**
 * Micronaut Kafka client used only by the analysis terminal-event publication adapter.
 */
@KafkaClient("analysis-events")
public interface AnalysisKafkaClient {

    /** Sends one serialized analysis Protobuf event and waits for broker acknowledgement. */
    void send(@Topic String topic, @KafkaKey String key, byte[] payload);
}
