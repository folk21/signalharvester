package io.signalharvester.results.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded filters for reading the current Results projection.
 */
public record ResultQueryCriteria(
        int limit,
        Optional<String> monitoringProfileId,
        Optional<String> sourceId,
        Optional<String> informationCategory,
        Optional<Boolean> relevant,
        Optional<String> classification,
        Optional<Instant> analyzedFrom,
        Optional<Instant> analyzedTo) {

    public static final int MIN_LIMIT = 1;
    public static final int MAX_LIMIT = 200;

    public ResultQueryCriteria {
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between " + MIN_LIMIT + " and " + MAX_LIMIT);
        }
        monitoringProfileId = requireOptionalText(monitoringProfileId, "monitoringProfileId");
        sourceId = requireOptionalText(sourceId, "sourceId");
        informationCategory = requireOptionalText(informationCategory, "informationCategory");
        relevant = Objects.requireNonNull(relevant, "relevant");
        classification = requireOptionalText(classification, "classification");
        analyzedFrom = Objects.requireNonNull(analyzedFrom, "analyzedFrom");
        analyzedTo = Objects.requireNonNull(analyzedTo, "analyzedTo");
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
