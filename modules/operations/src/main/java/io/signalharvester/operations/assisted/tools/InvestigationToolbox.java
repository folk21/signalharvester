package io.signalharvester.operations.assisted.tools;

import java.util.UUID;

/** Opens bounded read-only tool sessions scoped to one persisted Health Snapshot. */
public interface InvestigationToolbox {
    InvestigationToolSession openSession(UUID snapshotId);
}
