package io.signalharvester.results.application;

import java.util.Objects;
import java.util.Optional;

/** Filters applied to live analyzed-result delivery. */
public record ResultLiveCriteria(
        Optional<String> monitoringProfileId,
        Optional<String> sourceId,
        Optional<String> informationCategory,
        Optional<Boolean> relevant,
        Optional<String> classification) {

    public ResultLiveCriteria {
        monitoringProfileId = requireOptionalText(monitoringProfileId, "monitoringProfileId");
        sourceId = requireOptionalText(sourceId, "sourceId");
        informationCategory = requireOptionalText(informationCategory, "informationCategory");
        relevant = Objects.requireNonNull(relevant, "relevant");
        classification = requireOptionalText(classification, "classification");
    }

    private static Optional<String> requireOptionalText(Optional<String> value, String name) {
        Objects.requireNonNull(value, name);
        value.ifPresent(item -> {
            if (item.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank when present");
            }
        });
        return value;
    }
}
