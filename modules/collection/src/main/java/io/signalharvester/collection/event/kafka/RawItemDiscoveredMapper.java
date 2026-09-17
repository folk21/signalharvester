package io.signalharvester.collection.event.kafka;

import com.google.protobuf.Timestamp;
import io.signalharvester.collection.event.RawItemPublicationContext;
import io.signalharvester.collection.source.ExtractedSourceItem;
import io.signalharvester.events.collection.v1.KeywordAnalysisSettings;
import io.signalharvester.events.collection.v1.RawItemDiscovered;
import io.signalharvester.events.common.v1.EventEnvelope;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Maps collection-owned extracted items to the version-one Protobuf integration event. */
@Singleton
public final class RawItemDiscoveredMapper {

    static final String EVENT_TYPE = "collection.raw-item-discovered.v1";
    static final String PRODUCER = "collection";
    static final String SCHEMA_VERSION = "v1";

    private final Supplier<UUID> idSupplier;

    public RawItemDiscoveredMapper() {
        this(UUID::randomUUID);
    }

    RawItemDiscoveredMapper(Supplier<UUID> idSupplier) {
        this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier");
    }

    /** Creates a transport event while preserving extracted item metadata, correlation, and Analysis semantics. */
    RawItemDiscovered map(ExtractedSourceItem item, RawItemPublicationContext context) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(context, "context");

        String eventId = nextId("eventId");
        EventEnvelope.Builder envelope = EventEnvelope.newBuilder()
                .setEventId(eventId)
                .setEventType(EVENT_TYPE)
                .setOccurredAt(timestamp(item.discoveredAt()))
                .setCorrelationId(context.correlationId())
                .setProducer(PRODUCER)
                .setSchemaVersion(SCHEMA_VERSION);
        context.traceparent().ifPresent(envelope::setTraceparent);

        KeywordAnalysisSettings analysisSettings = KeywordAnalysisSettings.newBuilder()
                .addAllKeywords(context.analysisSettings().keywords())
                .setMinimumMatches(context.analysisSettings().minimumMatches())
                .build();
        RawItemDiscovered.Builder event = RawItemDiscovered.newBuilder()
                .setEnvelope(envelope)
                .setRawItemId(context.rawItemId())
                .setSourceId(item.sourceId().value().toString())
                .setMonitoringProfileId(context.monitoringProfileId())
                .setInformationCategory(context.informationCategory())
                .setUrl(item.url().toString())
                .setContent(item.content())
                .setContentType(item.contentType())
                .setAnalysisSettings(analysisSettings);
        item.externalId().ifPresent(event::setExternalId);
        item.title().ifPresent(event::setTitle);
        item.publishedAt().map(RawItemDiscoveredMapper::timestamp).ifPresent(event::setPublishedAt);
        return event.build();
    }

    private String nextId(String name) {
        UUID value = Objects.requireNonNull(idSupplier.get(), name + " supplier returned null");
        return value.toString();
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
