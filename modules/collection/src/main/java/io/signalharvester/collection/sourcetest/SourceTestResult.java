package io.signalharvester.collection.sourcetest;

import io.signalharvester.configuration.api.SourceId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Complete bounded diagnostic result for one persisted source-test execution. */
public record SourceTestResult(
        SourceId sourceId,
        SourceTestStatus status,
        Optional<Integer> httpStatus,
        Optional<String> responseContentType,
        int responseBytes,
        long fetchDurationMs,
        long extractionDurationMs,
        int candidateItemCount,
        List<SourceTestPreviewItem> preview,
        Optional<String> failureMessage) {

    public SourceTestResult {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(httpStatus, "httpStatus");
        Objects.requireNonNull(responseContentType, "responseContentType");
        Objects.requireNonNull(preview, "preview");
        Objects.requireNonNull(failureMessage, "failureMessage");
        preview = List.copyOf(preview);
        if (responseBytes < 0 || fetchDurationMs < 0 || extractionDurationMs < 0 || candidateItemCount < 0) {
            throw new IllegalArgumentException("Source-test counters and durations must not be negative");
        }
    }
}
