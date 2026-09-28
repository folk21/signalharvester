package io.signalharvester.operations.assisted.tools;

import java.util.Map;
import java.util.Objects;

/** Provider-facing tool description whose parameter schema is owned by the application. */
public record InvestigationToolDefinition(
        InvestigationToolName name,
        String description,
        Map<String, Object> parametersSchema) {

    public InvestigationToolDefinition {
        Objects.requireNonNull(name, "name");
        description = requireText(description, "description");
        parametersSchema = Map.copyOf(Objects.requireNonNull(parametersSchema, "parametersSchema"));
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
