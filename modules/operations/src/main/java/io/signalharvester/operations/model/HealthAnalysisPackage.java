package io.signalharvester.operations.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bounded sanitized evidence package suitable for manual or provider-backed LLM analysis. */
public record HealthAnalysisPackage(
        String packageVersion,
        UUID snapshotId,
        Instant generatedAt,
        String promptMarkdown,
        List<String> allowedEvidenceReferences,
        boolean reportTruncated) {

    public HealthAnalysisPackage {
        packageVersion = requireText(packageVersion, "packageVersion");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(generatedAt, "generatedAt");
        promptMarkdown = requireText(promptMarkdown, "promptMarkdown");
        allowedEvidenceReferences = List.copyOf(Objects.requireNonNull(allowedEvidenceReferences, "allowedEvidenceReferences"));
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return trimmed;
    }
}
