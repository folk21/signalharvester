package io.signalharvester.collection.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.collection.run.CollectionSourceResult;
import io.signalharvester.collection.run.CollectionSourceStatus;
import java.util.Optional;
import java.util.UUID;

/** REST representation of one source outcome inside a collection run. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Serdeable
public record CollectionSourceRunResponse(
        UUID sourceId,
        CollectionSourceStatus status,
        Optional<String> rawItemId,
        Optional<String> eventId,
        Optional<String> failureMessage) {

    static CollectionSourceRunResponse from(CollectionSourceResult result) {
        return new CollectionSourceRunResponse(
                result.sourceId().value(), result.status(), result.rawItemId(), result.eventId(), result.failureMessage());
    }
}
