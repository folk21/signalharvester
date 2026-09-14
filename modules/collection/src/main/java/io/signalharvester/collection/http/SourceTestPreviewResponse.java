package io.signalharvester.collection.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.collection.sourcetest.SourceTestPreviewItem;
import java.time.Instant;

/** Public bounded preview of one item extracted during a diagnostic source test. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Serdeable
public record SourceTestPreviewResponse(
        String externalId,
        String title,
        String url,
        String contentPreview,
        String contentType,
        Instant publishedAt,
        boolean contentTruncated) {

    /** Maps the internal source-test preview to the external REST representation. */
    public static SourceTestPreviewResponse from(SourceTestPreviewItem item) {
        return new SourceTestPreviewResponse(
                item.externalId().orElse(null),
                item.title().orElse(null),
                item.url().toString(),
                item.contentPreview(),
                item.contentType(),
                item.publishedAt().orElse(null),
                item.contentTruncated());
    }
}
