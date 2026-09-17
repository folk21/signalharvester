package io.signalharvester.analysis.event.kafka;

import com.google.protobuf.Timestamp;
import io.signalharvester.analysis.configuration.KeywordAnalysisConfiguration;
import io.signalharvester.analysis.model.DiscoveredRawItem;
import io.signalharvester.analysis.model.KeywordAnalysisSettings;
import io.signalharvester.events.collection.v1.RawItemDiscovered;
import jakarta.inject.Singleton;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Decodes RawItemDiscovered transport fields into the Analysis-owned raw application model. */
@Singleton
public final class RawItemDiscoveredMapper {

    private final KeywordAnalysisConfiguration legacyDefaults;

    public RawItemDiscoveredMapper(KeywordAnalysisConfiguration legacyDefaults) {
        this.legacyDefaults = Objects.requireNonNull(legacyDefaults, "legacyDefaults");
    }

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
                analysisSettings(event),
                event.hasExternalId() ? Optional.of(event.getExternalId()) : Optional.empty(),
                event.hasTitle() ? Optional.of(event.getTitle()) : Optional.empty(),
                URI.create(event.getUrl()),
                event.getContent(),
                event.getContentType(),
                event.hasPublishedAt() ? Optional.of(instant(event.getPublishedAt())) : Optional.empty());
    }

    private KeywordAnalysisSettings analysisSettings(RawItemDiscovered event) {
        if (event.hasAnalysisSettings()) {
            return new KeywordAnalysisSettings(
                    event.getAnalysisSettings().getKeywordsList(),
                    event.getAnalysisSettings().getMinimumMatches());
        }
        return new KeywordAnalysisSettings(legacyDefaults.getKeywords(), legacyDefaults.getMinimumMatches());
    }

    private static Optional<String> optional(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static Instant instant(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }
}
