package io.signalharvester.operations.http;

import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.operations.model.HealthAnalysisPackage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** REST representation of one bounded sanitized LLM analysis package. */
@Serdeable
public record HealthAnalysisPackageResponse(
        String packageVersion,
        UUID snapshotId,
        Instant generatedAt,
        String promptMarkdown,
        List<String> allowedEvidenceReferences,
        boolean reportTruncated) {

    static HealthAnalysisPackageResponse from(HealthAnalysisPackage value) {
        return new HealthAnalysisPackageResponse(
                value.packageVersion(),
                value.snapshotId(),
                value.generatedAt(),
                value.promptMarkdown(),
                value.allowedEvidenceReferences(),
                value.reportTruncated());
    }
}
