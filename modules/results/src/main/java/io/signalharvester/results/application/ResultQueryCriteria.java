package io.signalharvester.results.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded filters, optional text search, and optional continuation cursor for reading the current Results projection.
 */
public record ResultQueryCriteria(
        int limit,
        Optional<String> monitoringProfileId,
        Optional<String> sourceId,
        Optional<String> informationCategory,
        Optional<Boolean> relevant,
        Optional<String> classification,
        Optional<Instant> analyzedFrom,
        Optional<Instant> analyzedTo,
        Optional<String> search,
        Optional<String> cursor) {

    public static final int MIN_LIMIT = 1;
    public static final int MAX_LIMIT = 200;
    public static final int MAX_SEARCH_LENGTH = 200;
    public static final int MAX_CURSOR_LENGTH = 1024;

    /** Creates criteria without text search or keyset continuation for existing internal callers. */
    public ResultQueryCriteria(
            int limit,
            Optional<String> monitoringProfileId,
            Optional<String> sourceId,
            Optional<String> informationCategory,
            Optional<Boolean> relevant,
            Optional<String> classification,
            Optional<Instant> analyzedFrom,
            Optional<Instant> analyzedTo) {
        this(
                limit,
                monitoringProfileId,
                sourceId,
                informationCategory,
                relevant,
                classification,
                analyzedFrom,
                analyzedTo,
                Optional.empty(),
                Optional.empty());
    }

    public ResultQueryCriteria {
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new InvalidResultQueryException("limit must be between " + MIN_LIMIT + " and " + MAX_LIMIT);
        }
        monitoringProfileId = requireOptionalText(monitoringProfileId, "monitoringProfileId", Integer.MAX_VALUE);
        sourceId = requireOptionalText(sourceId, "sourceId", Integer.MAX_VALUE);
        informationCategory = requireOptionalText(informationCategory, "informationCategory", Integer.MAX_VALUE);
        relevant = Objects.requireNonNull(relevant, "relevant");
        classification = requireOptionalText(classification, "classification", Integer.MAX_VALUE);
        analyzedFrom = Objects.requireNonNull(analyzedFrom, "analyzedFrom");
        analyzedTo = Objects.requireNonNull(analyzedTo, "analyzedTo");
        search = requireOptionalText(search, "search", MAX_SEARCH_LENGTH).map(String::trim);
        cursor = requireOptionalText(cursor, "cursor", MAX_CURSOR_LENGTH);

        if (analyzedFrom.isPresent() && analyzedTo.isPresent()
                && analyzedFrom.orElseThrow().isAfter(analyzedTo.orElseThrow())) {
            throw new InvalidResultQueryException("analyzedFrom must not be after analyzedTo");
        }
    }

    private static Optional<String> requireOptionalText(Optional<String> value, String name, int maxLength) {
        Objects.requireNonNull(value, name);
        value.ifPresent(item -> {
            if (item.isBlank()) {
                throw new InvalidResultQueryException(name + " must not be blank when present");
            }
            if (item.length() > maxLength) {
                throw new InvalidResultQueryException(name + " exceeds the supported length of " + maxLength);
            }
        });
        return value;
    }
}
