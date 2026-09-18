package io.signalharvester.eventobservation.event.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.env.Environment;
import io.micronaut.core.naming.conventions.StringConvention;
import io.signalharvester.eventobservation.application.DeadLetterRecovery;
import io.signalharvester.eventobservation.application.DeadLetterRecoveryException;
import io.signalharvester.eventobservation.application.EventObservationRecorder;
import io.signalharvester.eventobservation.configuration.EventObservationKafkaReliabilityConfiguration;
import io.signalharvester.eventobservation.model.ObservedEventInput;
import io.signalharvester.events.failure.v1.DeadLetterEvent;
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

/** Reads Event Observation DLQ records and re-records their original bytes without republishing shared events. */
@Singleton
public final class KafkaEventObservationDeadLetterRecoveryService implements DeadLetterRecovery {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaEventObservationDeadLetterRecoveryService.class);
    private static final String CONSUMER_NAME = "event-observation";
    private static final Duration MAX_READ_TIMEOUT = Duration.ofSeconds(10);

    private final Environment environment;
    private final EventObservationKafkaReliabilityConfiguration configuration;
    private final String consumerGroup;
    private final EventObservationKafkaRecordDecoder decoder;
    private final EventObservationRecorder recorder;
    private final String rawTopic;
    private final String analyzedTopic;
    private final String rejectedTopic;
    private final Set<String> allowedSourceTopics;
    private final Semaphore permits;

    public KafkaEventObservationDeadLetterRecoveryService(
            Environment environment,
            EventObservationKafkaReliabilityConfiguration configuration,
            @Value("${signalharvester.event-observation.consumer-group:signalharvester-event-observation-v1}") String consumerGroup,
            EventObservationKafkaRecordDecoder decoder,
            EventObservationRecorder recorder,
            @Value("${signalharvester.kafka.raw-item-discovered-topic:signalharvester.collection.raw-item-discovered.v1}")
                    String rawTopic,
            @Value("${signalharvester.kafka.item-analyzed-topic:signalharvester.analysis.item-analyzed.v1}")
                    String analyzedTopic,
            @Value("${signalharvester.kafka.item-rejected-topic:signalharvester.analysis.item-rejected.v1}")
                    String rejectedTopic) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.consumerGroup = requireNonBlank(consumerGroup, "consumerGroup");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.rawTopic = requireNonBlank(rawTopic, "rawTopic");
        this.analyzedTopic = requireNonBlank(analyzedTopic, "analyzedTopic");
        this.rejectedTopic = requireNonBlank(rejectedTopic, "rejectedTopic");
        this.allowedSourceTopics = Set.of(this.rawTopic, this.analyzedTopic, this.rejectedTopic);
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
                    "Replayed Event Observation dead letter {} from {}-{}@{} through the owning application path",
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
        final ObservedEventInput observed;
        try {
            if (rawTopic.equals(event.getSourceTopic())) {
                observed = decoder.decodeRaw(
                        key, payload, event.getSourceTopic(), event.getSourcePartition(), event.getSourceOffset());
            } else if (analyzedTopic.equals(event.getSourceTopic())) {
                observed = decoder.decodeAnalyzed(
                        key, payload, event.getSourceTopic(), event.getSourcePartition(), event.getSourceOffset());
            } else if (rejectedTopic.equals(event.getSourceTopic())) {
                observed = decoder.decodeRejected(
                        key, payload, event.getSourceTopic(), event.getSourcePartition(), event.getSourceOffset());
            } else {
                throw invalidRecord(
                        "Event Observation dead-letter source topic is not an allowed observed topic", null);
            }
        } catch (DeadLetterRecoveryException exception) {
            throw exception;
        } catch (RuntimeException invalidSourceRecord) {
            throw new DeadLetterRecoveryException(
                    DeadLetterRecoveryException.Reason.INVALID_RECORD,
                    "Original Event Observation source record is still invalid under the current decoder",
                    invalidSourceRecord);
        }
        try {
            recorder.record(observed);
        } catch (RuntimeException replayFailure) {
            throw new DeadLetterRecoveryException(
                    DeadLetterRecoveryException.Reason.REPLAY_FAILED,
                    "Event Observation replay failed under the current application state",
                    replayFailure);
        }
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
                    "Unable to read the Event Observation dead-letter topic",
                    kafkaFailure);
        }
    }

    private LoadedDeadLetter validate(ConsumerRecord<String, byte[]> record, String deadLetterTopic) {
        if (record.value() == null) {
            throw invalidRecord("Event Observation dead-letter record has no value", null);
        }
        final DeadLetterEvent event;
        try {
            event = DeadLetterEvent.parseFrom(record.value());
        } catch (InvalidProtocolBufferException exception) {
            throw invalidRecord("Event Observation dead-letter record is not a valid DeadLetterEvent", exception);
        }
        String canonicalId = canonicalDeadLetterId(event);
        if (!event.getDeadLetterId().equals(canonicalId) || !Objects.equals(record.key(), event.getDeadLetterId())) {
            throw invalidRecord("Event Observation dead-letter identity does not match its source metadata", null);
        }
        if (!CONSUMER_NAME.equals(event.getConsumer())) {
            throw invalidRecord("Dead-letter record does not belong to the Event Observation consumer", null);
        }
        if (!consumerGroup.equals(event.getConsumerGroup())) {
            throw invalidRecord("Dead-letter record does not belong to the configured Event Observation consumer group", null);
        }
        if (!allowedSourceTopics.contains(event.getSourceTopic())) {
            throw invalidRecord("Event Observation dead-letter source topic is not an allowed observed topic", null);
        }
        if (event.getSourcePartition() < 0 || event.getSourceOffset() < 0 || event.getAttempts() < 1) {
            throw invalidRecord("Event Observation dead-letter source metadata is invalid", null);
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
        properties.put(ConsumerConfig.CLIENT_ID_CONFIG, "signalharvester-event-observation-dead-letter-recovery");
        return properties;
    }

    private <T> T withPermit(Supplier<T> work) {
        if (!permits.tryAcquire()) {
            throw new DeadLetterRecoveryException(
                    DeadLetterRecoveryException.Reason.BUSY,
                    "Event Observation dead-letter recovery is already at its configured concurrency limit");
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
