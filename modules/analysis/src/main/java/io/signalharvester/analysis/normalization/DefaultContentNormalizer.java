package io.signalharvester.analysis.normalization;

import io.signalharvester.analysis.model.DiscoveredRawItem;
import io.signalharvester.analysis.model.NormalizedContentItem;
import jakarta.inject.Singleton;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Provides deterministic baseline text/URL normalization before deduplication and analysis.
 *
 * <p>This first implementation deliberately performs only transport-independent normalization. It
 * does not parse HTML/RSS/API-specific fields; source-specific extraction remains collection-owned.</p>
 */
@Singleton
public final class DefaultContentNormalizer implements ContentNormalizer {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private final NormalizedItemIdentityFactory identityFactory;

    public DefaultContentNormalizer(NormalizedItemIdentityFactory identityFactory) {
        this.identityFactory = Objects.requireNonNull(identityFactory, "identityFactory");
    }

    @Override
    public NormalizedContentItem normalize(DiscoveredRawItem rawItem) {
        Objects.requireNonNull(rawItem, "rawItem");
        URI normalizedUrl = normalizeUrl(rawItem.url());
        String normalizedContent = collapseWhitespace(rawItem.content());
        Optional<String> normalizedExternalId = rawItem.externalId().map(String::strip).filter(value -> !value.isEmpty());
        Optional<String> normalizedTitle = rawItem.title().map(DefaultContentNormalizer::collapseWhitespace)
                .filter(value -> !value.isEmpty());
        String normalizedItemId = identityFactory.identityFor(
                rawItem.sourceId(), normalizedExternalId, normalizedUrl, normalizedContent);

        return new NormalizedContentItem(
                rawItem.sourceEventId(),
                rawItem.correlationId(),
                rawItem.traceparent(),
                rawItem.discoveredAt(),
                rawItem.rawItemId(),
                normalizedItemId,
                rawItem.sourceId(),
                rawItem.monitoringProfileId(),
                rawItem.informationCategory(),
                normalizedExternalId,
                normalizedTitle,
                normalizedUrl,
                normalizedContent,
                rawItem.contentType().strip(),
                Map.of(),
                rawItem.publishedAt());
    }

    static String collapseWhitespace(String value) {
        String stripped = Objects.requireNonNull(value, "value").strip();
        return stripped.isEmpty() ? "" : WHITESPACE.matcher(stripped).replaceAll(" ");
    }

    static URI normalizeUrl(URI value) {
        Objects.requireNonNull(value, "value");
        URI normalized = value.normalize();
        String scheme = normalized.getScheme().toLowerCase(Locale.ROOT);
        String host = normalized.getHost().toLowerCase(Locale.ROOT);
        int port = normalized.getPort();
        if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
            port = -1;
        }
        String path = normalized.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        StringBuilder canonical = new StringBuilder(scheme)
                .append("://")
                .append(host);
        if (port >= 0) {
            canonical.append(':').append(port);
        }
        canonical.append(path);
        if (normalized.getRawQuery() != null) {
            canonical.append('?').append(normalized.getRawQuery());
        }
        return URI.create(canonical.toString());
    }
}
