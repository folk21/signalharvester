package io.signalharvester.operations.application;

import io.signalharvester.operations.model.HealthSnapshot;
import java.util.List;

/** Replaceable internal boundary for versioned deterministic/statistical health interpretation. */
interface HealthEngine {
    HealthEvaluation evaluate(HealthSignalEvidence evidence, List<HealthSnapshot> baselineSnapshots);
}
