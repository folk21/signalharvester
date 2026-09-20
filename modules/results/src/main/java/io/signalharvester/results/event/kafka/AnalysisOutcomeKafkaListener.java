package io.signalharvester.results.event.kafka;

import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.OffsetReset;
import io.micronaut.configuration.kafka.annotation.OffsetStrategy;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.context.annotation.Requires;
import io.signalharvester.results.application.AnalysisOutcomeProjector;
import io.signalharvester.results.configuration.ResultsKafkaReliabilityConfiguration;
import io.signalharvester.results.model.AnalyzedResult;
import io.signalharvester.results.model.RejectedResult;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kafka ingress adapter that materializes terminal Analysis events into the Results read model.
 * The listener returns only after durable persistence or acknowledged terminal dead-letter publication,
 * after which Micronaut commits the consumed offset synchronously per record.
 */
@KafkaListener(
        value = "${signalharvester.results.consumer-group:signalharvester-results-v1}",
        offsetReset = OffsetReset.EARLIEST,
        offsetStrategy = OffsetStrategy.SYNC_PER_RECORD)
@Requires(property = "signalharvester.results.enabled", value = "true")
public class AnalysisOutcomeKafkaListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnalysisOutcomeKafkaListener.class);
    private static final Duration MAX_RETRY_BACKOFF = Duration.ofSeconds(5);

    private final AnalysisOutcomeKafkaRecordDecoder decoder;
    private final AnalysisOutcomeProjector projector;
    private final ResultsKafkaReliabilityConfiguration reliabilityConfiguration;
    private final ResultsDeadLetterPublisher deadLetterPublisher;

    public AnalysisOutcomeKafkaListener(
            AnalysisOutcomeKafkaRecordDecoder decoder,
            AnalysisOutcomeProjector projector,
            ResultsKafkaReliabilityConfiguration reliabilityConfiguration,
            ResultsDeadLetterPublisher deadLetterPublisher) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
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
            String topic) {
        requireRecordArguments(payload, topic);
        final AnalyzedResult result;
        try {
            result = decoder.decodeAnalyzed(key, payload);
        } catch (RuntimeException permanentFailure) {
            deadLetterPermanent(key, payload, offset, partition, topic, permanentFailure);
            return;
        }
        processRetryable(key, payload, offset, partition, topic, () -> projector.projectAnalyzed(result));
    }

    /** Materializes one rejected item with bounded retry and terminal dead-letter handling. */
    @Topic("${signalharvester.kafka.item-rejected-topic:signalharvester.analysis.item-rejected.v1}")
    public void receiveRejected(
            @KafkaKey String key,
            byte[] payload,
            long offset,
            int partition,
            String topic) {
        requireRecordArguments(payload, topic);
        final RejectedResult result;
        try {
            result = decoder.decodeRejected(key, payload);
        } catch (RuntimeException permanentFailure) {
            deadLetterPermanent(key, payload, offset, partition, topic, permanentFailure);
            return;
        }
        processRetryable(key, payload, offset, partition, topic, () -> projector.projectRejected(result));
    }

    private void processRetryable(
            String key,
            byte[] payload,
            long offset,
            int partition,
            String topic,
            Runnable processing) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                processing.run();
                break;
            } catch (RuntimeException retryableFailure) {
                if (attempt >= reliabilityConfiguration.getMaxAttempts()) {
                    deadLetterPublisher.publish(
                            topic, partition, offset, key, payload, retryableFailure, attempt, true);
                    logDeadLetter(topic, partition, offset, attempt, retryableFailure);
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

    private void deadLetterPermanent(
            String key,
            byte[] payload,
            long offset,
            int partition,
            String topic,
            RuntimeException failure) {
        deadLetterPublisher.publish(topic, partition, offset, key, payload, failure, 1, false);
        logDeadLetter(topic, partition, offset, 1, failure);
    }

    private static void requireRecordArguments(byte[] payload, String topic) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(topic, "topic");
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
