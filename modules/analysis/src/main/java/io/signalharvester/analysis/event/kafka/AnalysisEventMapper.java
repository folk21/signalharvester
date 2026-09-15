package io.signalharvester.analysis.event.kafka;

import com.google.protobuf.Timestamp;
import io.signalharvester.analysis.configuration.AnalysisClockFactory;
import io.signalharvester.analysis.event.AnalyzedItem;
import io.signalharvester.analysis.event.RejectedItem;
import io.signalharvester.analysis.model.NormalizedContentItem;
import io.signalharvester.events.analysis.v1.ItemAnalyzed;
import io.signalharvester.events.analysis.v1.ItemRejected;
import io.signalharvester.events.common.v1.EventEnvelope;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Maps analysis-owned semantic outcomes to version-one Protobuf integration events.
 */
@Singleton
public final class AnalysisEventMapper {

    static final String ANALYZED_EVENT_TYPE = "analysis.item-analyzed.v1";
    static final String REJECTED_EVENT_TYPE = "analysis.item-rejected.v1";
    static final String PRODUCER = "analysis";
    static final String SCHEMA_VERSION = "v1";

    private final Clock clock;
    private final Supplier<UUID> idSupplier;

    public AnalysisEventMapper(@Named(AnalysisClockFactory.ANALYSIS_CLOCK) Clock clock) {
        this(clock, UUID::randomUUID);
    }

    AnalysisEventMapper(Clock clock, Supplier<UUID> idSupplier) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier");
    }

    public ItemAnalyzed mapAnalyzed(AnalyzedItem analyzedItem) {
        NormalizedContentItem item = analyzedItem.item();
        var decision = analyzedItem.decision();
        ItemAnalyzed.Builder builder = ItemAnalyzed.newBuilder()
                .setEnvelope(envelope(item, ANALYZED_EVENT_TYPE))
                .setSourceEventId(item.sourceEventId())
                .setRawItemId(item.rawItemId())
                .setNormalizedItemId(item.normalizedItemId())
                .setSourceId(item.sourceId())
                .setMonitoringProfileId(item.monitoringProfileId())
                .setInformationCategory(item.informationCategory())
                .setUrl(item.url().toString())
                .setNormalizedContent(item.content())
                .setContentType(item.contentType())
                .putAllAttributes(item.attributes())
                .setRelevant(decision.relevant())
                .setClassification(decision.classification())
                .setScore(decision.score())
                .addAllTags(decision.tags())
                .setExplanation(decision.explanation())
                .setAnalyzer(decision.analyzer());
        item.externalId().ifPresent(builder::setExternalId);
        item.title().ifPresent(builder::setTitle);
        item.publishedAt().map(AnalysisEventMapper::timestamp).ifPresent(builder::setPublishedAt);
        return builder.build();
    }

    public ItemRejected mapRejected(RejectedItem rejectedItem) {
        NormalizedContentItem item = rejectedItem.item();
        return ItemRejected.newBuilder()
                .setEnvelope(envelope(item, REJECTED_EVENT_TYPE))
                .setSourceEventId(item.sourceEventId())
                .setRawItemId(item.rawItemId())
                .setNormalizedItemId(item.normalizedItemId())
                .setSourceId(item.sourceId())
                .setMonitoringProfileId(item.monitoringProfileId())
                .setInformationCategory(item.informationCategory())
                .setReasonCode(rejectedItem.reasonCode())
                .setExplanation(rejectedItem.explanation())
                .build();
    }

    private EventEnvelope envelope(NormalizedContentItem item, String eventType) {
        EventEnvelope.Builder builder = EventEnvelope.newBuilder()
                .setEventId(nextEventId())
                .setEventType(eventType)
                .setOccurredAt(timestamp(clock.instant()))
                .setCorrelationId(item.correlationId())
                .setProducer(PRODUCER)
                .setSchemaVersion(SCHEMA_VERSION);
        item.traceparent().ifPresent(builder::setTraceparent);
        return builder.build();
    }

    private String nextEventId() {
        return Objects.requireNonNull(idSupplier.get(), "event id supplier returned null").toString();
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
