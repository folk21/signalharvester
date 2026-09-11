package io.signalharvester.analysis.event.kafka;

import com.google.protobuf.Timestamp;
import io.signalharvester.analysis.model.DiscoveredRawItem;
import io.signalharvester.events.collection.v1.RawItemDiscovered;
import jakarta.inject.Singleton;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;

/**
 * Decodes the semantic fields of RawItemDiscovered into the analysis-owned raw application model.
 */
@Singleton
public final class RawItemDiscoveredMapper {

    DiscoveredRawItem map(RawItemDiscovered event) {
        var envelope = event.getEnvelope();
        return new DiscoveredRawItem(
                envelope.getEventId(),
                envelope.getCorrelationId(),
                optional(envelope.getTraceparent()),
                instant(envelope.getOccurredAt()),
                event.getRawItemId(),
                event.getSourceId(),
                event.getMonitoringProfileId(),
                event.getInformationCategory(),
                event.hasExternalId() ? Optional.of(event.getExternalId()) : Optional.empty(),
                event.hasTitle() ? Optional.of(event.getTitle()) : Optional.empty(),
                URI.create(event.getUrl()),
                event.getContent(),
                event.getContentType(),
                event.hasPublishedAt() ? Optional.of(instant(event.getPublishedAt())) : Optional.empty());
    }

    private static Optional<String> optional(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static Instant instant(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }
}
