package io.signalharvester.operations.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.signalharvester.operations.assisted.AssistedInvestigationTrial;
import io.signalharvester.operations.model.IncidentAssessment;
import io.signalharvester.operations.model.IncidentAssessmentSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies the runtime capture maps exactly to the versioned offline-evaluator artifact contract. */
class AssistedInvestigationEvidenceResponseTest {

    @Test
    void shouldMapCompletedTrialToVersionOneArtifact() {
        UUID snapshotId = UUID.randomUUID();
        IncidentAssessment assessment = new IncidentAssessment(
                UUID.randomUUID(),
                snapshotId,
                Instant.parse("2026-10-02T10:00:00Z"),
                IncidentAssessmentSource.PROVIDER,
                "fake",
                "deterministic-v1",
                "Kafka lag is elevated.",
                List.of("KAFKA"),
                0.8,
                List.of("Lag increased."),
                List.of("Analysis consumption may be behind."),
                List.of("health-snapshot:" + snapshotId),
                List.of("Inspect consumer progress."),
                true);
        AssistedInvestigationTrial trial = new AssistedInvestigationTrial(
                UUID.randomUUID(),
                "scenario-run-1",
                snapshotId,
                Instant.parse("2026-10-02T10:00:01Z"),
                assessment,
                2,
                3,
                1_250,
                List.of("health-snapshot:" + snapshotId),
                List.of("prometheus:kafka-lag"),
                8,
                4,
                45_000);

        AssistedInvestigationEvidenceResponse response = AssistedInvestigationEvidenceResponse.from(trial);

        assertEquals(1, response.schemaVersion());
        assertEquals("signalharvester-assisted-investigation-evidence", response.artifactType());
        assertEquals("signalharvester-runtime", response.source().runner());
        assertEquals("fake", response.source().provider());
        assertEquals("deterministic-v1", response.source().model());
        assertEquals(8, response.budgets().maxToolCalls());
        assertEquals(4, response.budgets().maxRounds());
        assertEquals(45_000, response.budgets().maxInvestigationDurationMs());
        assertEquals(1, response.trials().size());
        var exported = response.trials().getFirst();
        assertEquals("COMPLETED", exported.status());
        assertEquals("scenario-run-1", exported.scenarioRunId());
        assertEquals(snapshotId, exported.snapshotId());
        assertEquals(2, exported.execution().toolCallCount());
        assertEquals(3, exported.execution().roundCount());
        assertEquals(1_250, exported.execution().durationMs());
        assertTrue(exported.claimAnnotations().isEmpty());
        assertEquals(assessment.id(), exported.assessment().id());
    }
}
