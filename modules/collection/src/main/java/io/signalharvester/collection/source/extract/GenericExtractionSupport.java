package io.signalharvester.collection.source.extract;

import io.signalharvester.configuration.api.SourceId;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/** Shared value conversion helpers for configuration-driven source extraction. */
final class GenericExtractionSupport {

    private GenericExtractionSupport() {
    }

    static URI resolveItemUrl(SourceId sourceId, URI baseUri, Optional<String> value) {
        if (value.isEmpty() || value.orElseThrow().isBlank()) {
            return withoutFragment(baseUri);
        }
        try {
            URI resolved = baseUri.resolve(value.orElseThrow().strip());
            validateHttpUrl(resolved);
            return withoutFragment(resolved);
        } catch (IllegalArgumentException failure) {
            throw new SourceItemExtractionException(sourceId, "Extracted item URL is invalid", failure);
        }
    }

    static Optional<Instant> parseOptionalInstant(SourceId sourceId, Optional<String> value) {
        if (value.isEmpty() || value.orElseThrow().isBlank()) {
            return Optional.empty();
        }
        String candidate = value.orElseThrow().strip();
        for (DateTimeFormatter formatter : List.of(
                DateTimeFormatter.RFC_1123_DATE_TIME,
                DateTimeFormatter.ISO_DATE_TIME)) {
            try {
                if (formatter == DateTimeFormatter.RFC_1123_DATE_TIME) {
                    return Optional.of(ZonedDateTime.parse(candidate, formatter).toInstant());
                }
                return Optional.of(OffsetDateTime.parse(candidate, formatter).toInstant());
            } catch (DateTimeParseException ignored) {
                // Try the next supported representation.
            }
        }
        try {
            return Optional.of(Instant.parse(candidate));
        } catch (DateTimeParseException failure) {
            throw new SourceItemExtractionException(
                    sourceId, "Extracted publication timestamp is not a supported date-time", failure);
        }
    }

    static byte[] identityPayload(
            Optional<String> externalId,
            Optional<String> title,
            URI url,
            String content,
            Optional<Instant> publishedAt) {
        List<byte[]> fields = List.of(
                externalId.orElse("").getBytes(StandardCharsets.UTF_8),
                title.orElse("").getBytes(StandardCharsets.UTF_8),
                url.toASCIIString().getBytes(StandardCharsets.UTF_8),
                content.getBytes(StandardCharsets.UTF_8),
                publishedAt.map(Instant::toString).orElse("").getBytes(StandardCharsets.UTF_8));
        int size = fields.stream().mapToInt(field -> Integer.BYTES + field.length).sum();
        ByteBuffer canonical = ByteBuffer.allocate(size);
        for (byte[] field : fields) {
            canonical.putInt(field.length);
            canonical.put(field);
        }
        return canonical.array();
    }

    static Optional<String> nonBlank(Optional<String> value) {
        return value.map(String::strip).filter(candidate -> !candidate.isEmpty());
    }

    private static void validateHttpUrl(URI uri) {
        if (!uri.isAbsolute()
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getUserInfo() != null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("URL must be an absolute HTTP(S) URL with a host and no credentials");
        }
    }

    private static URI withoutFragment(URI uri) {
        if (uri.getFragment() == null) {
            return uri;
        }
        try {
            return new URI(
                    uri.getScheme(),
                    uri.getUserInfo(),
                    uri.getHost(),
                    uri.getPort(),
                    uri.getPath(),
                    uri.getQuery(),
                    null);
        } catch (URISyntaxException impossible) {
            throw new IllegalArgumentException("Unable to remove URI fragment", impossible);
        }
    }
}
