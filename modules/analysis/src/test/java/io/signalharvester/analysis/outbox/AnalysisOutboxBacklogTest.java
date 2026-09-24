package io.signalharvester.analysis.outbox;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Protects consistency of Analysis outbox backlog telemetry snapshots. */
class AnalysisOutboxBacklogTest {

    /** Reject internally inconsistent pending-count and oldest-row combinations. */
    @Test
    void shouldRejectInconsistentSnapshot() {
        Instant createdAt = Instant.parse("2026-09-24T10:00:00Z");

        assertThrows(IllegalArgumentException.class, () -> new AnalysisOutboxBacklog(-1, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new AnalysisOutboxBacklog(0, Optional.of(createdAt)));
        assertThrows(IllegalArgumentException.class, () -> new AnalysisOutboxBacklog(1, Optional.empty()));
    }
}
