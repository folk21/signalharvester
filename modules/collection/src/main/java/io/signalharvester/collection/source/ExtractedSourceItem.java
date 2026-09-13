package io.signalharvester.collection.source;

import io.signalharvester.configuration.api.SourceId;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Collection-owned semantic item extracted from one fetched source response before Kafka publication.
 *
 * @param sourceId configured source identifier
 * @param url canonical/source-provided item URL
 * @param externalId source-provided stable item identity when available
 * @param title source-provided item title when available
 * @param content textual item content used by downstream normalization and analysis
 * @param contentType semantic content type of {@code content}
 * @param publishedAt source publication timestamp when available
 * @param discoveredAt collection timestamp inherited from the fetched response
 * @param identityPayload deterministic payload used only for raw-item identity calculation
 */
public record ExtractedSourceItem(
        SourceId sourceId,
        URI url,
        Optional<String> externalId,
        Optional<String> title,
        String content,
        String contentType,
        Optional<Instant> publishedAt,
        Instant discoveredAt,
        byte[] identityPayload) {

    public ExtractedSourceItem {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(content, "content");
        requireNonBlank(contentType, "contentType");
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(discoveredAt, "discoveredAt");
        Objects.requireNonNull(identityPayload, "identityPayload");
        externalId.ifPresent(value -> requireNonBlank(value, "externalId"));
        title.ifPresent(value -> requireNonBlank(value, "title"));
        validateUrl(url);
        identityPayload = identityPayload.clone();
    }

    @Override
    public byte[] identityPayload() {
        return identityPayload.clone();
    }

    private static void validateUrl(URI url) {
        if (!url.isAbsolute()) {
            throw new IllegalArgumentException("url must be absolute");
        }
        String scheme = url.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("url scheme must be HTTP or HTTPS");
        }
        if (url.getHost() == null || url.getHost().isBlank()) {
            throw new IllegalArgumentException("url must contain a host");
        }
        if (url.getUserInfo() != null) {
            throw new IllegalArgumentException("url must not contain embedded credentials");
        }
        if (url.getFragment() != null) {
            throw new IllegalArgumentException("url must not contain a fragment");
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
