package io.signalharvester.operations.assisted.tools;

import java.util.Map;
import java.util.Objects;

/** One provider-requested invocation of an application-authorized read-only investigation tool. */
public record InvestigationToolRequest(
        String callId,
        InvestigationToolName tool,
        Map<String, String> arguments) {

    public InvestigationToolRequest {
        callId = requireText(callId, "callId", 256);
        Objects.requireNonNull(tool, "tool");
        arguments = Map.copyOf(Objects.requireNonNull(arguments, "arguments"));
        if (arguments.size() > 16) {
            throw new IllegalArgumentException("arguments must contain at most 16 entries");
        }
        arguments.forEach((key, value) -> {
            requireText(key, "argument key", 128);
            requireText(value, "argument value", 4_096);
        });
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
