package io.signalharvester.operations.model;

import java.util.List;
import java.util.Objects;

/** Validated provider/manual content before runtime-owned assessment metadata is attached. */
public record IncidentAssessmentDraft(
        String summary,
        List<String> suspectedSubsystems,
        double confidence,
        List<String> observations,
        List<String> hypotheses,
        List<String> evidenceReferences,
        List<String> recommendedChecks,
        boolean humanAttentionSuggested) {

    private static final int MAX_SUMMARY_LENGTH = 4_000;
    private static final int MAX_ITEM_LENGTH = 1_000;

    public IncidentAssessmentDraft {
        summary = requireText(summary, "summary", MAX_SUMMARY_LENGTH);
        suspectedSubsystems = boundedTextList(suspectedSubsystems, "suspectedSubsystems", 16, 128);
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        observations = boundedTextList(observations, "observations", 32, MAX_ITEM_LENGTH);
        hypotheses = boundedTextList(hypotheses, "hypotheses", 32, MAX_ITEM_LENGTH);
        evidenceReferences = boundedTextList(evidenceReferences, "evidenceReferences", 64, 256);
        recommendedChecks = boundedTextList(recommendedChecks, "recommendedChecks", 32, MAX_ITEM_LENGTH);
    }

    private static List<String> boundedTextList(List<String> values, String name, int maxSize, int maxLength) {
        Objects.requireNonNull(values, name);
        if (values.size() > maxSize) {
            throw new IllegalArgumentException(name + " must contain at most " + maxSize + " items");
        }
        return values.stream().map(value -> requireText(value, name + " item", maxLength)).toList();
    }

    private static String requireText(String value, String name, int maxLength) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(name + " must be at most " + maxLength + " characters");
        }
        return trimmed;
    }
}
