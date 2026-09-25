package io.signalharvester.operations.application;

import io.signalharvester.operations.api.OperationalChangeRecord;
import io.signalharvester.operations.model.HealthSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/** Nearest persisted Health Snapshots and bounded numeric deltas around one journaled change. */
public record OperationalHealthCorrelation(
        OperationalChangeRecord change,
        HealthSnapshot before,
        HealthSnapshot after,
        Integer healthScoreDelta,
        Boolean statusChanged,
        Map<String, Double> signalDeltas) {

    static OperationalHealthCorrelation between(
            OperationalChangeRecord change,
            HealthSnapshot before,
            HealthSnapshot after) {
        if (before == null || after == null) {
            return new OperationalHealthCorrelation(change, before, after, null, null, Map.of());
        }
        Map<String, Double> deltas = new LinkedHashMap<>();
        TreeSet<String> keys = new TreeSet<>(before.signalValues().keySet());
        keys.retainAll(after.signalValues().keySet());
        for (String key : keys) {
            double delta = after.signalValues().get(key) - before.signalValues().get(key);
            if (Double.isFinite(delta)) {
                deltas.put(key, delta);
            }
        }
        return new OperationalHealthCorrelation(
                change,
                before,
                after,
                after.healthScore() - before.healthScore(),
                before.overallStatus() != after.overallStatus(),
                Map.copyOf(deltas));
    }
}
