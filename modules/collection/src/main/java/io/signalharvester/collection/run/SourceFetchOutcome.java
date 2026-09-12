package io.signalharvester.collection.run;

import io.signalharvester.collection.source.FetchedSourceContent;
import io.signalharvester.collection.source.SourceFetchException;
import io.signalharvester.configuration.api.ConfiguredSource;
import java.util.Objects;

/**
 * Internal best-effort fetch outcome for one configured source.
 */
sealed interface SourceFetchOutcome permits SourceFetchOutcome.Success, SourceFetchOutcome.Failure {

    ConfiguredSource source();

    /** Successful fetch outcome. */
    record Success(ConfiguredSource source, FetchedSourceContent content) implements SourceFetchOutcome {
        public Success {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(content, "content");
        }
    }

    /** Failed fetch outcome retained without aborting unrelated source work. */
    record Failure(ConfiguredSource source, SourceFetchException cause) implements SourceFetchOutcome {
        public Failure {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(cause, "cause");
        }
    }
}
