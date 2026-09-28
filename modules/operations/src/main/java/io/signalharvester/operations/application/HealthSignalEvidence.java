package io.signalharvester.operations.application;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded signal sample plus explicit collection gaps used as input to health evaluation. */
public record HealthSignalEvidence(Map<String, Double> values, List<String> unknownReasons) {
    public HealthSignalEvidence {
        values = Map.copyOf(Objects.requireNonNull(values, "values"));
        unknownReasons = List.copyOf(Objects.requireNonNull(unknownReasons, "unknownReasons"));
    }
}
