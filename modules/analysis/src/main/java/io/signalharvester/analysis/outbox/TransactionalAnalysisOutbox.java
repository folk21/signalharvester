package io.signalharvester.analysis.outbox;

import io.signalharvester.analysis.configuration.AnalysisKafkaConfiguration;
import io.signalharvester.analysis.event.AnalysisPublicationResult;
import io.signalharvester.analysis.event.AnalyzedItem;
import io.signalharvester.analysis.event.RejectedItem;
import io.signalharvester.analysis.event.kafka.AnalysisEventMapper;
import io.signalharvester.events.analysis.v1.ItemAnalyzed;
import io.signalharvester.events.analysis.v1.ItemRejected;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.Objects;

/** Maps terminal Analysis outcomes and appends their exact Kafka bytes to the current JDBC transaction. */
@Singleton
public final class TransactionalAnalysisOutbox implements AnalysisOutbox {

    private final AnalysisOutboxStore store;
    private final AnalysisKafkaConfiguration configuration;
    private final AnalysisEventMapper mapper;

    public TransactionalAnalysisOutbox(
            AnalysisOutboxStore store,
            AnalysisKafkaConfiguration configuration,
            AnalysisEventMapper mapper) {
        this.store = Objects.requireNonNull(store, "store");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public AnalysisPublicationResult enqueueAnalyzed(AnalyzedItem analyzedItem) {
        Objects.requireNonNull(analyzedItem, "analyzedItem");
        ItemAnalyzed event = mapper.mapAnalyzed(analyzedItem);
        String topic = configuration.getItemAnalyzedTopic();
        append(event.getEnvelope().getEventId(), topic, event.getNormalizedItemId(), event.toByteArray(),
                Instant.ofEpochSecond(
                        event.getEnvelope().getOccurredAt().getSeconds(),
                        event.getEnvelope().getOccurredAt().getNanos()));
        return new AnalysisPublicationResult(event.getEnvelope().getEventId(), topic, event.getNormalizedItemId());
    }

    @Override
    public AnalysisPublicationResult enqueueRejected(RejectedItem rejectedItem) {
        Objects.requireNonNull(rejectedItem, "rejectedItem");
        ItemRejected event = mapper.mapRejected(rejectedItem);
        String topic = configuration.getItemRejectedTopic();
        append(event.getEnvelope().getEventId(), topic, event.getNormalizedItemId(), event.toByteArray(),
                Instant.ofEpochSecond(
                        event.getEnvelope().getOccurredAt().getSeconds(),
                        event.getEnvelope().getOccurredAt().getNanos()));
        return new AnalysisPublicationResult(event.getEnvelope().getEventId(), topic, event.getNormalizedItemId());
    }

    private void append(String eventId, String topic, String eventKey, byte[] payload, Instant createdAt) {
        store.append(new AnalysisOutboxEntry(eventId, topic, eventKey, payload, createdAt, 0));
    }
}
