package io.signalharvester.collection.sourcetest;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Bounded diagnostic view of one semantic item extracted during a source test. */
public record SourceTestPreviewItem(
        Optional<String> externalId,
        Optional<String> title,
        URI url,
        String contentPreview,
        String contentType,
        Optional<Instant> publishedAt,
        boolean contentTruncated) {

    public SourceTestPreviewItem {
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(contentPreview, "contentPreview");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(publishedAt, "publishedAt");
    }
}
