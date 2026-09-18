package io.signalharvester.eventobservation.testing;

import io.signalharvester.eventobservation.model.ObservedEvent;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Builds readable Event Observation fixtures while hiding optional transport fields from individual tests. */
public final class ObservedEventFixture {

    private ObservedEventFixture() {
    }

    /** Starts a fixture with the required technical-event identity. */
    public static Builder observedEvent(long observationId, String eventId, String eventType) {
        return new Builder(observationId, eventId, eventType);
    }

    public static final class Builder {
        private final long observationId;
        private final String eventId;
        private final String eventType;
        private Instant occurredAt = Instant.parse("2026-09-14T10:00:00Z");
        private Instant observedAt = Instant.parse("2026-09-14T10:00:01Z");
        private String correlationId = "run-1";
        private String traceparent;
        private String producer = "analysis";
        private String schemaVersion = "v1";
        private String kafkaTopic = "topic-a";
        private int kafkaPartition;
        private long kafkaOffset = 1L;
        private String kafkaKey = "event-key";
        private String payloadType = "ItemAnalyzed";
        private String sourceEventId;
        private String rawItemId;
        private String normalizedItemId;
        private String sourceId = "source-1";
        private String monitoringProfileId = "profile-1";
        private String informationCategory = "JOB";
        private String externalId;
        private String title;
        private String url;
        private String contentType;
        private Boolean relevant;
        private String classification;
        private Integer score;
        private String analyzer;
        private String reasonCode;
        private String explanation;

        private Builder(long observationId, String eventId, String eventType) {
            if (observationId <= 0) {
                throw new IllegalArgumentException("observationId must be positive");
            }
            this.observationId = observationId;
            this.eventId = Objects.requireNonNull(eventId, "eventId");
            this.eventType = Objects.requireNonNull(eventType, "eventType");
        }

        public Builder occurredAt(Instant value) {
            occurredAt = Objects.requireNonNull(value, "occurredAt");
            return this;
        }

        public Builder observedAt(Instant value) {
            observedAt = Objects.requireNonNull(value, "observedAt");
            return this;
        }

        public Builder correlationId(String value) {
            correlationId = Objects.requireNonNull(value, "correlationId");
            return this;
        }

        public Builder traceparent(String value) {
            traceparent = value;
            return this;
        }

        public Builder producer(String value) {
            producer = Objects.requireNonNull(value, "producer");
            return this;
        }

        public Builder topic(String value) {
            kafkaTopic = Objects.requireNonNull(value, "topic");
            return this;
        }

        public Builder offset(long value) {
            kafkaOffset = value;
            return this;
        }

        public Builder key(String value) {
            kafkaKey = Objects.requireNonNull(value, "key");
            return this;
        }

        public Builder payloadType(String value) {
            payloadType = Objects.requireNonNull(value, "payloadType");
            return this;
        }

        public Builder sourceEventId(String value) {
            sourceEventId = value;
            return this;
        }

        public Builder rawItemId(String value) {
            rawItemId = value;
            return this;
        }

        public Builder normalizedItemId(String value) {
            normalizedItemId = value;
            return this;
        }

        public Builder externalId(String value) {
            externalId = value;
            return this;
        }

        public Builder title(String value) {
            title = value;
            return this;
        }

        public Builder url(String value) {
            url = value;
            return this;
        }

        public Builder contentType(String value) {
            contentType = value;
            return this;
        }

        public Builder relevant(Boolean value) {
            relevant = value;
            return this;
        }

        public Builder classification(String value) {
            classification = value;
            return this;
        }

        public Builder score(Integer value) {
            score = value;
            return this;
        }

        public Builder analyzer(String value) {
            analyzer = value;
            return this;
        }

        public Builder reasonCode(String value) {
            reasonCode = value;
            return this;
        }

        public Builder explanation(String value) {
            explanation = value;
            return this;
        }

        public ObservedEvent build() {
            return new ObservedEvent(
                    observationId,
                    eventId,
                    eventType,
                    occurredAt,
                    observedAt,
                    correlationId,
                    Optional.ofNullable(traceparent),
                    producer,
                    schemaVersion,
                    kafkaTopic,
                    kafkaPartition,
                    kafkaOffset,
                    kafkaKey,
                    payloadType,
                    Optional.ofNullable(sourceEventId),
                    Optional.ofNullable(rawItemId),
                    Optional.ofNullable(normalizedItemId),
                    Optional.ofNullable(sourceId),
                    Optional.ofNullable(monitoringProfileId),
                    Optional.ofNullable(informationCategory),
                    Optional.ofNullable(externalId),
                    Optional.ofNullable(title),
                    Optional.ofNullable(url),
                    Optional.ofNullable(contentType),
                    Optional.ofNullable(relevant),
                    Optional.ofNullable(classification),
                    score == null ? OptionalInt.empty() : OptionalInt.of(score),
                    Optional.ofNullable(analyzer),
                    Optional.ofNullable(reasonCode),
                    Optional.ofNullable(explanation));
        }
    }
}
