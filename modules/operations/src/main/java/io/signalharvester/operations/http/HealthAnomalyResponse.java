package io.signalharvester.operations.http;

import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.operations.model.HealthAnomaly;

/** REST representation of one structured deterministic/statistical health anomaly. */
@Serdeable
public record HealthAnomalyResponse(
        String signal,
        String detector,
        String severity,
        double observedValue,
        Double referenceValue,
        double anomalyScore,
        String evidence) {

    static HealthAnomalyResponse from(HealthAnomaly anomaly) {
        return new HealthAnomalyResponse(
                anomaly.signal(),
                anomaly.detector(),
                anomaly.severity().name(),
                anomaly.observedValue(),
                anomaly.referenceValue(),
                anomaly.anomalyScore(),
                anomaly.evidence());
    }
}
