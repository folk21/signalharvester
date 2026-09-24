package io.signalharvester.operations.http;

import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.operations.application.OperationalHealthCorrelation;

/** REST representation of nearest before/after Health Snapshots around one change. */
@Serdeable
public record OperationalHealthCorrelationResponse(
        OperationalChangeResponse change,
        HealthSnapshotResponse before,
        HealthSnapshotResponse after) {

    static OperationalHealthCorrelationResponse from(OperationalHealthCorrelation correlation) {
        return new OperationalHealthCorrelationResponse(
                OperationalChangeResponse.from(correlation.change()),
                correlation.before() == null ? null : HealthSnapshotResponse.from(correlation.before()),
                correlation.after() == null ? null : HealthSnapshotResponse.from(correlation.after()));
    }
}
