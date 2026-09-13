package io.signalharvester.collection.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.collection.api.CollectionRunResult;
import io.signalharvester.collection.api.CollectionRunStatus;
import java.time.Instant;
import java.util.List;

/** REST representation of durable collection-run operational state. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Serdeable
public record CollectionRunResponse(
        String collectionRunId,
        String monitoringProfileId,
        String informationCategory,
        Instant startedAt,
        Instant finishedAt,
        CollectionRunStatus status,
        long publishedCount,
        long failedCount,
        List<CollectionSourceRunResponse> sources) {

    static CollectionRunResponse from(CollectionRunResult result) {
        return new CollectionRunResponse(
                result.collectionRunId(),
                result.monitoringProfileId(),
                result.informationCategory(),
                result.startedAt(),
                result.finishedAt(),
                result.status(),
                result.publishedCount(),
                result.failedCount(),
                result.sources().stream().map(CollectionSourceRunResponse::from).toList());
    }
}
