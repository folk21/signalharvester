package io.signalharvester.collection.http;

import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.collection.run.CollectionRunResult;
import io.signalharvester.collection.run.CollectionRunStatus;
import java.time.Instant;
import java.util.List;

/** REST representation of durable collection-run operational state. */
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
