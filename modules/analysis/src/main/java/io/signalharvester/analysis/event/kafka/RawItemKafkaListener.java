package io.signalharvester.analysis.event.kafka;

import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.OffsetReset;
import io.micronaut.configuration.kafka.annotation.OffsetStrategy;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.context.annotation.Requires;
import io.signalharvester.analysis.application.RawItemProcessor;
import io.signalharvester.analysis.configuration.AnalysisKafkaReliabilityConfiguration;
import io.signalharvester.analysis.model.DiscoveredRawItem;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kafka ingress adapter for version-one collection raw-item events.
 *
 * <p>The listener completes only after successful processing or acknowledged dead-letter publication.
 * Micronaut then commits the consumed offset synchronously per record. Deterministically invalid
 * records skip retries, while runtime failures use the configured bounded retry policy before terminal handling.</p>
 */
@KafkaListener(
        value = "${signalharvester.analysis.consumer-group:signalharvester-analysis-v1}",
        offsetReset = OffsetReset.EARLIEST,
        offsetStrategy = OffsetStrategy.SYNC_PER_RECORD)
@Requires(property = "signalharvester.analysis.enabled", notEquals = "false", defaultValue = "true")
public class RawItemKafkaListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(RawItemKafkaListener.class);
    private static final Duration MAX_RETRY_BACKOFF = Duration.ofSeconds(5);

    private final RawItemKafkaRecordDecoder decoder;
    private final RawItemProcessor processor;
    private final AnalysisKafkaReliabilityConfiguration reliabilityConfiguration;
    private final AnalysisDeadLetterPublisher deadLetterPublisher;

    public RawItemKafkaListener(
            RawItemKafkaRecordDecoder decoder,
            RawItemProcessor processor,
            AnalysisKafkaReliabilityConfiguration reliabilityConfiguration,
            AnalysisDeadLetterPublisher deadLetterPublisher) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.reliabilityConfiguration = Objects.requireNonNull(reliabilityConfiguration, "reliabilityConfiguration");
        this.deadLetterPublisher = Objects.requireNonNull(deadLetterPublisher, "deadLetterPublisher");
        requireValidBackoff(reliabilityConfiguration.getRetryBackoff());
    }

    /**
     * Decodes and processes one raw item with bounded retries before per-record framework commit.
     */
    @Topic("${signalharvester.kafka.raw-item-discovered-topic:signalharvester.collection.raw-item-discovered.v1}")
    public void receive(
            @KafkaKey String key,
            byte[] payload,
            long offset,
            int partition,
            String topic) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(topic, "topic");

        final DiscoveredRawItem rawItem;
        try {
            rawItem = decoder.decode(key, payload);
        } catch (RuntimeException permanentFailure) {
            propagateIfInterrupted(permanentFailure);
            deadLetterPublisher.publish(
                    topic, partition, offset, key, payload, permanentFailure, 1, false);
            logDeadLetter(topic, partition, offset, 1, permanentFailure);
            return;
        }

        int attempt = 0;
        while (true) {
            attempt++;
            try {
                processor.process(rawItem);
                break;
            } catch (RuntimeException retryableFailure) {
                propagateIfInterrupted(retryableFailure);
                if (attempt >= reliabilityConfiguration.getMaxAttempts()) {
                    deadLetterPublisher.publish(
                            topic, partition, offset, key, payload, retryableFailure, attempt, true);
                    logDeadLetter(topic, partition, offset, attempt, retryableFailure);
                    return;
                }
                LOGGER.warn(
                        "Analysis Kafka processing attempt {} failed for {}-{}@{}; retrying",
                        attempt,
                        topic,
                        partition,
                        offset,
                        retryableFailure);
                sleepBeforeRetry(reliabilityConfiguration.getRetryBackoff());
            }
        }
    }


    private static void propagateIfInterrupted(RuntimeException failure) {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Interrupted while processing Analysis Kafka record", failure);
        }
    }

    private static void sleepBeforeRetry(Duration backoff) {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Interrupted while waiting to retry Analysis Kafka record");
        }
        if (backoff.isZero()) {
            return;
        }
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry Analysis Kafka record", interrupted);
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
                "Analysis Kafka record {}-{}@{} moved to dead letter after {} attempt(s)",
                topic,
                partition,
                offset,
                attempts,
                failure);
    }
}
