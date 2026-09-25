package io.signalharvester.operations.application;

import io.signalharvester.operations.api.OperationalChangeRecord;
import io.signalharvester.operations.model.HealthSnapshot;
import java.util.List;
import java.util.UUID;

/** Internal application boundary for operational timeline and Health Snapshot/report administration. */
public interface OperationalIntelligenceOperations {
    List<OperationalChangeRecord> recentChanges(int limit);
    HealthSnapshot captureHealthSnapshot();
    HealthSnapshot latestSnapshot();
    String latestMarkdownReport();
    OperationalHealthCorrelation correlateChange(UUID changeId);
}
