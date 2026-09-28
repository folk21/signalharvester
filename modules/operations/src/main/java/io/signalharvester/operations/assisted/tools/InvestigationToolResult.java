package io.signalharvester.operations.assisted.tools;

import java.util.List;
import java.util.Objects;

/** Bounded sanitized output from one read-only investigation tool call. */
public record InvestigationToolResult(
        String callId,
        InvestigationToolName tool,
        boolean success,
        String content,
        List<String> evidenceReferences) {

    public InvestigationToolResult {
        callId = requireText(callId, "callId");
        Objects.requireNonNull(tool, "tool");
        content = requireText(content, "content");
        evidenceReferences = List.copyOf(Objects.requireNonNull(evidenceReferences, "evidenceReferences"));
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
