package io.signalharvester.eventobservation.event.kafka;

import io.micronaut.context.annotation.Value;
import io.signalharvester.eventobservation.configuration.EventObservationKafkaReliabilityConfiguration;
import io.signalharvester.events.failure.v1.DeadLetterEvent;
import jakarta.inject.Singleton;
import java.util.Objects;

/** Maps failed Event Observation inputs to the shared dead-letter Protobuf contract and publishes them to Kafka. */
@Singleton
public final class KafkaEventObservationDeadLetterPublisher implements EventObservationDeadLetterPublisher {

    private static final int MAX_FAILURE_MESSAGE_CHARS = 1000;
    private static final String CONSUMER_NAME = "event-observation";

    private final EventObservationDeadLetterKafkaClient kafkaClient;
    private final EventObservationKafkaReliabilityConfiguration configuration;
    private final String consumerGroup;

    public KafkaEventObservationDeadLetterPublisher(
            EventObservationDeadLetterKafkaClient kafkaClient,
            EventObservationKafkaReliabilityConfiguration configuration,
            @Value("${signalharvester.event-observation.consumer-group:signalharvester-event-observation-v1}")
                    String consumerGroup) {
        this.kafkaClient = Objects.requireNonNull(kafkaClient, "kafkaClient");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.consumerGroup = requireNonBlank(consumerGroup, "consumerGroup");
    }

    @Override
    public void publish(
            String sourceTopic,
            int sourcePartition,
            long sourceOffset,
            String sourceKey,
            byte[] sourcePayload,
            RuntimeException failure,
            int attempts,
            boolean retryable) {
        Objects.requireNonNull(sourcePayload, "sourcePayload");
        Objects.requireNonNull(failure, "failure");
        String deadLetterId = deadLetterId(consumerGroup, sourceTopic, sourcePartition, sourceOffset);
        DeadLetterEvent event = DeadLetterEvent.newBuilder()
                .setDeadLetterId(deadLetterId)
                .setConsumer(CONSUMER_NAME)
                .setConsumerGroup(consumerGroup)
                .setSourceTopic(requireNonBlank(sourceTopic, "sourceTopic"))
                .setSourcePartition(sourcePartition)
                .setSourceOffset(sourceOffset)
                .setSourceKey(sourceKey == null ? "" : sourceKey)
                .setSourcePayload(com.google.protobuf.ByteString.copyFrom(sourcePayload))
                .setFailureType(failure.getClass().getName())
                .setFailureMessage(boundedMessage(failure.getMessage()))
                .setAttempts(attempts)
                .setRetryable(retryable)
                .build();
        kafkaClient.send(configuration.getDeadLetterTopic(), deadLetterId, event.toByteArray());
    }

    private static String deadLetterId(String consumerGroup, String topic, int partition, long offset) {
        return requireNonBlank(consumerGroup, "consumerGroup") + ":"
                + requireNonBlank(topic, "sourceTopic") + ":"
                + partition + ":" + offset;
    }

    private static String boundedMessage(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= MAX_FAILURE_MESSAGE_CHARS
                ? message
                : message.substring(0, MAX_FAILURE_MESSAGE_CHARS);
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
