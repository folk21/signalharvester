package io.signalharvester.operations.application;

import io.signalharvester.operations.api.OperationalChangeRecord;
import io.signalharvester.operations.model.HealthSnapshot;

/** Nearest persisted Health Snapshots around one journaled change; null sides mean no snapshot exists yet. */
public record OperationalHealthCorrelation(
        OperationalChangeRecord change,
        HealthSnapshot before,
        HealthSnapshot after) {
}
