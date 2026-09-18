package io.signalharvester.results.testing;

import io.signalharvester.results.application.ResultSummary;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Builds compact Results summary fixtures without exposing Optional construction in individual tests. */
public final class ResultSummaryFixture {

    private ResultSummaryFixture() {
    }

    /** Starts a summary fixture with the required logical result identity. */
    public static Builder resultSummary(String monitoringProfileId, String normalizedItemId) {
        return new Builder(monitoringProfileId, normalizedItemId);
    }

    public static final class Builder {
        private final String monitoringProfileId;
        private final String normalizedItemId;
        private String sourceId = "source-a";
        private String informationCategory = "JOB";
        private String externalId;
        private String title = "Senior Java Engineer";
        private String url = "https://example.test/jobs/1";
        private boolean relevant = true;
        private String classification = "MATCHED";
        private int score = 90;
        private Map<String, String> attributes = Map.of();
        private List<String> tags = List.of("java");
        private String explanation = "Matched";
        private String analyzer = "keyword-v1";
        private Instant publishedAt;
        private Instant analyzedAt = Instant.parse("2026-09-13T10:05:00Z");

        private Builder(String monitoringProfileId, String normalizedItemId) {
            this.monitoringProfileId = Objects.requireNonNull(monitoringProfileId, "monitoringProfileId");
            this.normalizedItemId = Objects.requireNonNull(normalizedItemId, "normalizedItemId");
        }

        public Builder source(String value) {
            sourceId = Objects.requireNonNull(value, "sourceId");
            return this;
        }

        public Builder externalId(String value) {
            externalId = value;
            return this;
        }

        public Builder title(String value) {
            title = value;
            return this;
        }

        public Builder url(String value) {
            url = Objects.requireNonNull(value, "url");
            return this;
        }

        public Builder score(int value) {
            score = value;
            return this;
        }

        public Builder attributes(Map<String, String> value) {
            attributes = Map.copyOf(Objects.requireNonNull(value, "attributes"));
            return this;
        }

        public Builder tags(List<String> value) {
            tags = List.copyOf(Objects.requireNonNull(value, "tags"));
            return this;
        }

        public Builder explanation(String value) {
            explanation = Objects.requireNonNull(value, "explanation");
            return this;
        }

        public Builder publishedAt(Instant value) {
            publishedAt = value;
            return this;
        }

        public Builder analyzedAt(Instant value) {
            analyzedAt = Objects.requireNonNull(value, "analyzedAt");
            return this;
        }

        public ResultSummary build() {
            return new ResultSummary(
                    monitoringProfileId,
                    normalizedItemId,
                    sourceId,
                    informationCategory,
                    Optional.ofNullable(externalId),
                    Optional.ofNullable(title),
                    url,
                    relevant,
                    classification,
                    score,
                    attributes,
                    tags,
                    explanation,
                    analyzer,
                    Optional.ofNullable(publishedAt),
                    analyzedAt);
        }
    }
}
