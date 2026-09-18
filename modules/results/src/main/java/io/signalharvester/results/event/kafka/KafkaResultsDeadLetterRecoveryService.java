package io.signalharvester.results.event.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.env.Environment;
import io.micronaut.core.naming.conventions.StringConvention;
import io.signalharvester.events.failure.v1.DeadLetterEvent;
import io.signalharvester.results.application.AnalysisOutcomeProjector;
import io.signalharvester.results.application.DeadLetterRecovery;
import io.signalharvester.results.application.DeadLetterRecoveryException;
import io.signalharvester.results.configuration.ResultsKafkaReliabilityConfiguration;
import io.signalharvester.results.model.AnalyzedResult;
import io.signalharvester.results.model.RejectedResult;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.OffsetOutOfRangeException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reads Results DLQ records by position and reprojects their original bytes without republishing shared events. */
@Singleton
public final class KafkaResultsDeadLetterRecoveryService implements DeadLetterRecovery {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaResultsDeadLetterRecoveryService.class);
    private static final String CONSUMER_NAME = "results";
    private static final Duration MAX_READ_TIMEOUT = Duration.ofSeconds(10);

    private final Environment environment;
    private final ResultsKafkaReliabilityConfiguration configuration;
    private final String consumerGroup;
    private final AnalysisOutcomeKafkaRecordDecoder decoder;
    private final AnalysisOutcomeProjector projector;
    private final String analyzedTopic;
    private final String rejectedTopic;
    private final Set<String> allowedSourceTopics;
    private final Semaphore permits;

    public KafkaResultsDeadLetterRecoveryService(
            Environment environment,
            ResultsKafkaReliabilityConfiguration configuration,
            @Value("${signalharvester.results.consumer-group:signalharvester-results-v1}") String consumerGroup,
            AnalysisOutcomeKafkaRecordDecoder decoder,
            AnalysisOutcomeProjector projector,
            @Value("${signalharvester.kafka.item-analyzed-topic:signalharvester.analysis.item-analyzed.v1}")
                    String analyzedTopic,
            @Value("${signalharvester.kafka.item-rejected-topic:signalharvester.analysis.item-rejected.v1}")
                    String rejectedTopic) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.consumerGroup = requireNonBlank(consumerGroup, "consumerGroup");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.projector = Objects.requireNonNull(projector, "projector");
        this.analyzedTopic = requireNonBlank(analyzedTopic, "analyzedTopic");
        this.rejectedTopic = requireNonBlank(rejectedTopic, "rejectedTopic");
        this.allowedSourceTopics = Set.of(this.analyzedTopic, this.rejectedTopic);
        requireValidReadTimeout(configuration.getReplayReadTimeout());
        this.permits = new Semaphore(configuration.getReplayMaxConcurrency(), true);
    }

    @Override
    public Inspection inspect(int deadLetterPartition, long deadLetterOffset) {
        return withPermit(() -> load(deadLetterPartition, deadLetterOffset).inspection());
    }

    @Override
    public Inspection replay(int deadLetterPartition, long deadLetterOffset, String expectedDeadLetterId) {
        return withPermit(() -> {
            LoadedDeadLetter loaded = load(deadLetterPartition, deadLetterOffset);
            requireConfirmation(loaded.event().getDeadLetterId(), expectedDeadLetterId);
            replaySourceRecord(loaded.event());
            LOGGER.info(
                    "Replayed Results dead letter {} from {}-{}@{} through the owning application path",
                    loaded.event().getDeadLetterId(),
                    loaded.event().getSourceTopic(),
                    loaded.event().getSourcePartition(),
                    loaded.event().getSourceOffset());
            return loaded.inspection();
        });
    }

    private void replaySourceRecord(DeadLetterEvent event) {
        String key = sourceKey(event);
        byte[] payload = event.getSourcePayload().toByteArray();
        if (analyzedTopic.equals(event.getSourceTopic())) {
            final AnalyzedResult result;
            try {
                result = decoder.decodeAnalyzed(key, payload);
            } catch (RuntimeException invalidSourceRecord) {
                throw invalidSourceRecord(invalidSourceRecord);
            }
            try {
                projector.projectAnalyzed(result);
            } catch (RuntimeException replayFailure) {
                throw replayFailed(replayFailure);
            }
            return;
        }
        if (rejectedTopic.equals(event.getSourceTopic())) {
            final RejectedResult result;
            try {
                result = decoder.decodeRejected(key, payload);
            } catch (RuntimeException invalidSourceRecord) {
                throw invalidSourceRecord(invalidSourceRecord);
            }
            try {
                projector.projectRejected(result);
            } catch (RuntimeException replayFailure) {
                throw replayFailed(replayFailure);
            }
            return;
        }
        throw invalidRecord("Results dead-letter source topic is not an allowed Results input topic", null);
    }

    private LoadedDeadLetter load(int deadLetterPartition, long deadLetterOffset) {
        requirePosition(deadLetterPartition, deadLetterOffset);
        String deadLetterTopic = configuration.getDeadLetterTopic();
        TopicPartition topicPartition = new TopicPartition(deadLetterTopic, deadLetterPartition);
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(consumerProperties())) {
            consumer.assign(List.of(topicPartition));
            consumer.seek(topicPartition, deadLetterOffset);
            ConsumerRecords<String, byte[]> records = consumer.poll(configuration.getReplayReadTimeout());
            ConsumerRecord<String, byte[]> record = records.records(topicPartition).stream()
                    .filter(candidate -> candidate.offset() == deadLetterOffset)
                    .findFirst()
                    .orElseThrow(() -> notFound(deadLetterTopic, deadLetterPartition, deadLetterOffset));
            return validate(record, deadLetterTopic);
        } catch (DeadLetterRecoveryException exception) {
            throw exception;
        } catch (OffsetOutOfRangeException exception) {
            throw notFound(deadLetterTopic, deadLetterPartition, deadLetterOffset);
        } catch (RuntimeException kafkaFailure) {
            throw new DeadLetterRecoveryException(
                    DeadLetterRecoveryException.Reason.KAFKA_UNAVAILABLE,
                    "Unable to read the Results dead-letter topic",
                    kafkaFailure);
        }
    }

    private LoadedDeadLetter validate(ConsumerRecord<String, byte[]> record, String deadLetterTopic) {
        if (record.value() == null) {
            throw invalidRecord("Results dead-letter record has no value", null);
        }
        final DeadLetterEvent event;
        try {
            event = DeadLetterEvent.parseFrom(record.value());
        } catch (InvalidProtocolBufferException exception) {
            throw invalidRecord("Results dead-letter record is not a valid DeadLetterEvent", exception);
        }
        String canonicalId = canonicalDeadLetterId(event);
        if (!event.getDeadLetterId().equals(canonicalId) || !Objects.equals(record.key(), event.getDeadLetterId())) {
            throw invalidRecord("Results dead-letter identity does not match its source metadata", null);
        }
        if (!CONSUMER_NAME.equals(event.getConsumer())) {
            throw invalidRecord("Dead-letter record does not belong to the Results consumer", null);
        }
        if (!consumerGroup.equals(event.getConsumerGroup())) {
            throw invalidRecord("Dead-letter record does not belong to the configured Results consumer group", null);
        }
        if (!allowedSourceTopics.contains(event.getSourceTopic())) {
            throw invalidRecord("Results dead-letter source topic is not an allowed Results input topic", null);
        }
        if (event.getSourcePartition() < 0 || event.getSourceOffset() < 0 || event.getAttempts() < 1) {
            throw invalidRecord("Results dead-letter source metadata is invalid", null);
        }
        Inspection inspection = new Inspection(
                event.getDeadLetterId(),
                event.getConsumer(),
                event.getConsumerGroup(),
                deadLetterTopic,
                record.partition(),
                record.offset(),
                event.getSourceTopic(),
                event.getSourcePartition(),
                event.getSourceOffset(),
                event.getSourceKey(),
                event.getFailureType(),
                event.getFailureMessage(),
                event.getAttempts(),
                event.getRetryable(),
                event.getSourcePayload().size());
        return new LoadedDeadLetter(event, inspection);
    }

    private Map<String, Object> consumerProperties() {
        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> configured = environment.getProperties("kafka", StringConvention.RAW);
        for (String name : ConsumerConfig.configNames()) {
            if (configured.containsKey(name)) {
                properties.put(name, configured.get(name));
            }
        }
        properties.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                environment.getProperty("kafka.bootstrap.servers", String.class).orElse("localhost:9092"));
        properties.remove(ConsumerConfig.GROUP_ID_CONFIG);
        properties.remove(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        properties.put(ConsumerConfig.CLIENT_ID_CONFIG, "signalharvester-results-dead-letter-recovery");
        return properties;
    }

    private <T> T withPermit(Supplier<T> work) {
        if (!permits.tryAcquire()) {
            throw new DeadLetterRecoveryException(
                    DeadLetterRecoveryException.Reason.BUSY,
                    "Results dead-letter recovery is already at its configured concurrency limit");
        }
        try {
            return work.get();
        } finally {
            permits.release();
        }
    }

    private static void requireConfirmation(String actualDeadLetterId, String expectedDeadLetterId) {
        if (expectedDeadLetterId == null || expectedDeadLetterId.isBlank()) {
            throw new DeadLetterRecoveryException(
                    DeadLetterRecoveryException.Reason.CONFIRMATION_FAILED,
                    "expectedDeadLetterId must not be blank");
        }
        if (!actualDeadLetterId.equals(expectedDeadLetterId)) {
            throw new DeadLetterRecoveryException(
                    DeadLetterRecoveryException.Reason.CONFIRMATION_FAILED,
                    "expectedDeadLetterId does not match the dead-letter record at the requested position");
        }
    }

    private static void requirePosition(int partition, long offset) {
        if (partition < 0 || offset < 0) {
            throw new IllegalArgumentException("dead-letter partition and offset must not be negative");
        }
    }

    private static String canonicalDeadLetterId(DeadLetterEvent event) {
        return requireNonBlank(event.getConsumerGroup(), "consumerGroup") + ":"
                + requireNonBlank(event.getSourceTopic(), "sourceTopic") + ":"
                + event.getSourcePartition() + ":" + event.getSourceOffset();
    }

    private static String sourceKey(DeadLetterEvent event) {
        return event.getSourceKey().isEmpty() ? null : event.getSourceKey();
    }

    private static DeadLetterRecoveryException invalidSourceRecord(RuntimeException cause) {
        return new DeadLetterRecoveryException(
                DeadLetterRecoveryException.Reason.INVALID_RECORD,
                "Original Results source record is still invalid under the current decoder",
                cause);
    }

    private static DeadLetterRecoveryException replayFailed(RuntimeException cause) {
        return new DeadLetterRecoveryException(
                DeadLetterRecoveryException.Reason.REPLAY_FAILED,
                "Results replay failed under the current application state",
                cause);
    }

    private static DeadLetterRecoveryException invalidRecord(String message, Throwable cause) {
        return cause == null
                ? new DeadLetterRecoveryException(DeadLetterRecoveryException.Reason.INVALID_RECORD, message)
                : new DeadLetterRecoveryException(DeadLetterRecoveryException.Reason.INVALID_RECORD, message, cause);
    }

    private static DeadLetterRecoveryException notFound(String topic, int partition, long offset) {
        return new DeadLetterRecoveryException(
                DeadLetterRecoveryException.Reason.NOT_FOUND,
                "Dead-letter record not found at " + topic + "-" + partition + "@" + offset);
    }

    private static void requireValidReadTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "replayReadTimeout");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_READ_TIMEOUT) > 0) {
            throw new IllegalArgumentException("replayReadTimeout must be greater than zero and at most " + MAX_READ_TIMEOUT);
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private record LoadedDeadLetter(DeadLetterEvent event, Inspection inspection) {
    }
}
