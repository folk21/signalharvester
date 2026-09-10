package io.signalharvester.collection.event.kafka;

import com.google.protobuf.Timestamp;
import io.signalharvester.collection.event.RawItemPublicationContext;
import io.signalharvester.collection.source.FetchedSourceContent;
import io.signalharvester.events.collection.v1.RawItemDiscovered;
import io.signalharvester.events.common.v1.EventEnvelope;
import jakarta.inject.Singleton;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Maps collection-owned fetched content to the version-one Protobuf integration event.
 *
 * <p>Generated Protobuf messages remain inside this Kafka mapping boundary. The current raw HTTP
 * transport produces one initial raw event per fetched payload; later source-specific extraction may
 * create more granular raw items without changing the publisher contract.</p>
 */
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

    /**
     * Creates a transport event while preserving source provenance and caller-supplied correlation.
     */
    RawItemDiscovered map(FetchedSourceContent content, RawItemPublicationContext context) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(context, "context");

        String eventId = nextId("eventId");

        EventEnvelope.Builder envelope = EventEnvelope.newBuilder()
                .setEventId(eventId)
                .setEventType(EVENT_TYPE)
                .setOccurredAt(timestamp(content.fetchedAt()))
                .setCorrelationId(context.correlationId())
                .setProducer(PRODUCER)
                .setSchemaVersion(SCHEMA_VERSION);
        context.traceparent().ifPresent(envelope::setTraceparent);

        String contentType = content.contentType().orElse("application/octet-stream");
        return RawItemDiscovered.newBuilder()
                .setEnvelope(envelope)
                .setRawItemId(context.rawItemId())
                .setSourceId(content.sourceId().value().toString())
                .setMonitoringProfileId(context.monitoringProfileId())
                .setInformationCategory(context.informationCategory())
                .setUrl(content.requestedUri().toString())
                .setContent(decodeBody(content.body(), contentType))
                .setContentType(contentType)
                .build();
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

    private static String decodeBody(byte[] body, String contentType) {
        return new String(body, charset(contentType));
    }

    private static Charset charset(String contentType) {
        for (String parameter : contentType.split(";")) {
            String trimmed = parameter.trim();
            int separator = trimmed.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String name = trimmed.substring(0, separator).trim();
            if (!"charset".equals(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String value = trimmed.substring(separator + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            if (!value.isBlank()) {
                return Charset.forName(value);
            }
        }
        return StandardCharsets.UTF_8;
    }
}
