package io.signalharvester.eventobservation.event.kafka;

import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.OffsetReset;
import io.micronaut.configuration.kafka.annotation.OffsetStrategy;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.context.annotation.Requires;
import io.signalharvester.eventobservation.application.EventObservationRecorder;
import io.signalharvester.eventobservation.configuration.EventObservationKafkaReliabilityConfiguration;
import io.signalharvester.eventobservation.model.ObservedEventInput;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Kafka ingress adapter that records selected published events for the technical Event Explorer. */
@KafkaListener(
        value = "${signalharvester.event-observation.consumer-group:signalharvester-event-observation-v1}",
        offsetReset = OffsetReset.EARLIEST,
        offsetStrategy = OffsetStrategy.DISABLED)
@Requires(property = "signalharvester.event-observation.enabled", value = "true")
public class EventObservationKafkaListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventObservationKafkaListener.class);
    private static final Duration MAX_RETRY_BACKOFF = Duration.ofSeconds(5);

    private final EventObservationKafkaRecordDecoder decoder;
    private final EventObservationRecorder recorder;
    private final EventObservationKafkaReliabilityConfiguration reliabilityConfiguration;
    private final EventObservationDeadLetterPublisher deadLetterPublisher;

    public EventObservationKafkaListener(
            EventObservationKafkaRecordDecoder decoder,
            EventObservationRecorder recorder,
            EventObservationKafkaReliabilityConfiguration reliabilityConfiguration,
            EventObservationDeadLetterPublisher deadLetterPublisher) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.reliabilityConfiguration = Objects.requireNonNull(reliabilityConfiguration, "reliabilityConfiguration");
        this.deadLetterPublisher = Objects.requireNonNull(deadLetterPublisher, "deadLetterPublisher");
        requireValidBackoff(reliabilityConfiguration.getRetryBackoff());
    }

    /** Records a raw-item discovery with bounded retry and terminal dead-letter handling. */
    @Topic("${signalharvester.kafka.raw-item-discovered-topic:signalharvester.collection.raw-item-discovered.v1}")
    public void receiveRaw(
            @KafkaKey String key, byte[] payload, long offset, int partition, String topic, Consumer<?, ?> consumer) {
        requireRecordArguments(payload, topic, consumer);
        final ObservedEventInput event;
        try {
            event = decoder.decodeRaw(key, payload, topic, partition, offset);
        } catch (RuntimeException permanentFailure) {
            deadLetterPermanentAndCommit(key, payload, offset, partition, topic, consumer, permanentFailure);
            return;
        }
        processRetryable(key, payload, offset, partition, topic, consumer, () -> recorder.record(event));
    }

    /** Records an analyzed-item event with bounded retry and terminal dead-letter handling. */
    @Topic("${signalharvester.kafka.item-analyzed-topic:signalharvester.analysis.item-analyzed.v1}")
    public void receiveAnalyzed(
            @KafkaKey String key, byte[] payload, long offset, int partition, String topic, Consumer<?, ?> consumer) {
        requireRecordArguments(payload, topic, consumer);
        final ObservedEventInput event;
        try {
            event = decoder.decodeAnalyzed(key, payload, topic, partition, offset);
        } catch (RuntimeException permanentFailure) {
            deadLetterPermanentAndCommit(key, payload, offset, partition, topic, consumer, permanentFailure);
            return;
        }
        processRetryable(key, payload, offset, partition, topic, consumer, () -> recorder.record(event));
    }

    /** Records a rejected-item event with bounded retry and terminal dead-letter handling. */
    @Topic("${signalharvester.kafka.item-rejected-topic:signalharvester.analysis.item-rejected.v1}")
    public void receiveRejected(
            @KafkaKey String key, byte[] payload, long offset, int partition, String topic, Consumer<?, ?> consumer) {
        requireRecordArguments(payload, topic, consumer);
        final ObservedEventInput event;
        try {
            event = decoder.decodeRejected(key, payload, topic, partition, offset);
        } catch (RuntimeException permanentFailure) {
            deadLetterPermanentAndCommit(key, payload, offset, partition, topic, consumer, permanentFailure);
            return;
        }
        processRetryable(key, payload, offset, partition, topic, consumer, () -> recorder.record(event));
    }

    private void processRetryable(
            String key,
            byte[] payload,
            long offset,
            int partition,
            String topic,
            Consumer<?, ?> consumer,
            Runnable processing) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                processing.run();
                commit(consumer, topic, partition, offset);
                return;
            } catch (RuntimeException retryableFailure) {
                if (attempt >= reliabilityConfiguration.getMaxAttempts()) {
                    deadLetterPublisher.publish(
                            topic, partition, offset, key, payload, retryableFailure, attempt, true);
                    logDeadLetter(topic, partition, offset, attempt, retryableFailure);
                    commit(consumer, topic, partition, offset);
                    return;
                }
                LOGGER.warn(
                        "Event Observation Kafka processing attempt {} failed for {}-{}@{}; retrying",
                        attempt,
                        topic,
                        partition,
                        offset,
                        retryableFailure);
                sleepBeforeRetry(reliabilityConfiguration.getRetryBackoff());
            }
        }
    }

    private void deadLetterPermanentAndCommit(
            String key,
            byte[] payload,
            long offset,
            int partition,
            String topic,
            Consumer<?, ?> consumer,
            RuntimeException failure) {
        deadLetterPublisher.publish(topic, partition, offset, key, payload, failure, 1, false);
        logDeadLetter(topic, partition, offset, 1, failure);
        commit(consumer, topic, partition, offset);
    }

    private static void requireRecordArguments(byte[] payload, String topic, Consumer<?, ?> consumer) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(consumer, "consumer");
    }

    private static void commit(Consumer<?, ?> consumer, String topic, int partition, long offset) {
        consumer.commitSync(Map.of(
                new TopicPartition(topic, partition),
                new OffsetAndMetadata(offset + 1)));
    }

    private static void sleepBeforeRetry(Duration backoff) {
        if (backoff.isZero()) {
            return;
        }
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry Event Observation Kafka record", interrupted);
        }
    }

    private static void requireValidBackoff(Duration backoff) {
        Objects.requireNonNull(backoff, "retryBackoff");
        if (backoff.isNegative() || backoff.compareTo(MAX_RETRY_BACKOFF) > 0) {
            throw new IllegalArgumentException("retryBackoff must be between zero and " + MAX_RETRY_BACKOFF);
        }
    }

    private static void logDeadLetter(
            String topic, int partition, long offset, int attempts, RuntimeException failure) {
        LOGGER.error(
                "Event Observation Kafka record {}-{}@{} moved to dead letter after {} attempt(s)",
                topic,
                partition,
                offset,
                attempts,
                failure);
    }
}
