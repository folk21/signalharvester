package io.signalharvester.operations.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Sanitized immutable input for one operational journal record. */
public record OperationalChangeRequest(
        OperationalChangeCategory category,
        OperationalChangeTargetType targetType,
        String targetId,
        Map<String, String> beforeState,
        Map<String, String> afterState,
        OperationalChangeOutcome outcome,
        OperationalChangeContext context) {

    public OperationalChangeRequest {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(context, "context");
        targetId = requireText(targetId, "targetId");
        beforeState = immutableCopy(beforeState);
        afterState = immutableCopy(afterState);
    }

    private static Map<String, String> immutableCopy(Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        return Map.copyOf(new LinkedHashMap<>(values));
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
