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
import io.signalharvester.results.configuration.ResultsKafkaReliabilityConfiguration;
import io.signalharvester.results.model.AnalyzedResult;
import io.signalharvester.results.model.RejectedResult;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kafka ingress adapter that materializes terminal Analysis events into the Results read model.
 * Offsets are committed after durable persistence or acknowledged terminal dead-letter publication.
 */
@KafkaListener(
        value = "${signalharvester.results.consumer-group:signalharvester-results-v1}",
        offsetReset = OffsetReset.EARLIEST,
        offsetStrategy = OffsetStrategy.DISABLED)
@Requires(property = "signalharvester.results.enabled", value = "true")
public class AnalysisOutcomeKafkaListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnalysisOutcomeKafkaListener.class);
    private static final Duration MAX_RETRY_BACKOFF = Duration.ofSeconds(5);

    private final AnalysisOutcomeMapper mapper;
    private final AnalysisOutcomeProjector projector;
    private final ResultsKafkaReliabilityConfiguration reliabilityConfiguration;
    private final ResultsDeadLetterPublisher deadLetterPublisher;

    public AnalysisOutcomeKafkaListener(
            AnalysisOutcomeMapper mapper,
            AnalysisOutcomeProjector projector,
            ResultsKafkaReliabilityConfiguration reliabilityConfiguration,
            ResultsDeadLetterPublisher deadLetterPublisher) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.projector = Objects.requireNonNull(projector, "projector");
        this.reliabilityConfiguration = Objects.requireNonNull(reliabilityConfiguration, "reliabilityConfiguration");
        this.deadLetterPublisher = Objects.requireNonNull(deadLetterPublisher, "deadLetterPublisher");
        requireValidBackoff(reliabilityConfiguration.getRetryBackoff());
    }

    /** Materializes one analyzed item with bounded retry and terminal dead-letter handling. */
    @Topic("${signalharvester.kafka.item-analyzed-topic:signalharvester.analysis.item-analyzed.v1}")
    public void receiveAnalyzed(
            @KafkaKey String key,
            byte[] payload,
            long offset,
            int partition,
            String topic,
            Consumer<?, ?> consumer) {
        requireRecordArguments(payload, topic, consumer);
        final AnalyzedResult result;
        try {
            ItemAnalyzed event = parseAnalyzed(payload);
            requireKey(key, event.getNormalizedItemId(), "ItemAnalyzed.normalized_item_id");
            result = mapper.map(event);
        } catch (RuntimeException permanentFailure) {
            deadLetterPermanentAndCommit(key, payload, offset, partition, topic, consumer, permanentFailure);
            return;
        }
        processRetryable(key, payload, offset, partition, topic, consumer, () -> projector.projectAnalyzed(result));
    }

    /** Materializes one rejected item with bounded retry and terminal dead-letter handling. */
    @Topic("${signalharvester.kafka.item-rejected-topic:signalharvester.analysis.item-rejected.v1}")
    public void receiveRejected(
            @KafkaKey String key,
            byte[] payload,
            long offset,
            int partition,
            String topic,
            Consumer<?, ?> consumer) {
        requireRecordArguments(payload, topic, consumer);
        final RejectedResult result;
        try {
            ItemRejected event = parseRejected(payload);
            String expectedKey = event.hasNormalizedItemId() ? event.getNormalizedItemId() : event.getRawItemId();
            requireKey(key, expectedKey, "ItemRejected identity");
            result = mapper.map(event);
        } catch (RuntimeException permanentFailure) {
            deadLetterPermanentAndCommit(key, payload, offset, partition, topic, consumer, permanentFailure);
            return;
        }
        processRetryable(key, payload, offset, partition, topic, consumer, () -> projector.projectRejected(result));
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
                        "Results Kafka processing attempt {} failed for {}-{}@{}; retrying",
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

    private static ItemAnalyzed parseAnalyzed(byte[] payload) {
        try {
            return ItemAnalyzed.parseFrom(payload);
        } catch (InvalidProtocolBufferException exception) {
            throw new IllegalArgumentException("Invalid ItemAnalyzed Protobuf payload", exception);
        }
    }

    private static ItemRejected parseRejected(byte[] payload) {
        try {
            return ItemRejected.parseFrom(payload);
        } catch (InvalidProtocolBufferException exception) {
            throw new IllegalArgumentException("Invalid ItemRejected Protobuf payload", exception);
        }
    }

    private static void requireKey(String actualKey, String expectedKey, String identityDescription) {
        if (actualKey == null || !actualKey.equals(expectedKey)) {
            throw new IllegalArgumentException("Kafka key must match " + identityDescription);
        }
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
            throw new IllegalStateException("Interrupted while waiting to retry Results Kafka record", interrupted);
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
                "Results Kafka record {}-{}@{} moved to dead letter after {} attempt(s)",
                topic,
                partition,
                offset,
                attempts,
                failure);
    }
}
