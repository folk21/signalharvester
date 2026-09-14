package io.signalharvester.collection.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.collection.sourcetest.SourceTestResult;
import io.signalharvester.collection.sourcetest.SourceTestStatus;
import java.util.List;
import java.util.UUID;

/** Public diagnostic result returned after testing one configured source. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Serdeable
public record SourceTestResponse(
        UUID sourceId,
        SourceTestStatus status,
        Integer httpStatus,
        String responseContentType,
        int responseBytes,
        long fetchDurationMs,
        long extractionDurationMs,
        int candidateItemCount,
        List<SourceTestPreviewResponse> preview,
        String failureMessage) {

    /** Maps the internal diagnostic outcome to the external REST representation. */
    public static SourceTestResponse from(SourceTestResult result) {
        return new SourceTestResponse(
                result.sourceId().value(),
                result.status(),
                result.httpStatus().orElse(null),
                result.responseContentType().orElse(null),
                result.responseBytes(),
                result.fetchDurationMs(),
                result.extractionDurationMs(),
                result.candidateItemCount(),
                result.preview().stream().map(SourceTestPreviewResponse::from).toList(),
                result.failureMessage().orElse(null));
    }
}
