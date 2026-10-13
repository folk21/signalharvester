package io.signalharvester.operations.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.serde.ObjectMapper;
import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.operations.alert.HumanAttentionAlert;
import io.signalharvester.operations.alert.HumanAttentionAlertReason;
import io.signalharvester.operations.alert.HumanAttentionAlertSeverity;
import io.signalharvester.operations.alert.HumanAttentionAlertState;
import io.signalharvester.operations.model.HealthStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Protects the human-attention alert REST representation and its Micronaut Serde boundary. */
class HumanAttentionAlertResponseTest {

    /** Keep the response compile-time serializable so alert-history reads can emit JSON. */
    @Test
    void shouldSerializeAlertHistoryResponse() throws Exception {
        HumanAttentionAlertResponse response = responseFixture();

        String json = ObjectMapper.getDefault().writeValueAsString(List.of(response));

        assertTrue(HumanAttentionAlertResponse.class.isAnnotationPresent(Serdeable.class));
        assertTrue(json.contains("\"id\":\"b497184c-d3e1-4fe2-9660-015c89042c95\""));
        assertTrue(json.contains("\"state\":\"OPEN\""));
        assertTrue(json.contains("\"severity\":\"CRITICAL\""));
        assertTrue(json.contains("\"latestHealthStatus\":\"UNHEALTHY\""));
    }

    /** Map persisted alert lifecycle evidence without changing its public REST values. */
    @Test
    void shouldMapPersistedAlertToResponse() {
        UUID alertId = UUID.fromString("b497184c-d3e1-4fe2-9660-015c89042c95");
        UUID snapshotId = UUID.fromString("267eccca-cd10-4c13-bf20-209ed84f24cd");
        Instant openedAt = Instant.parse("2026-10-12T02:04:12.158846Z");
        HumanAttentionAlertResponse response = responseFixture();

        assertEquals(alertId, response.id());
        assertEquals(HumanAttentionAlertState.OPEN, response.state());
        assertEquals(HumanAttentionAlertSeverity.CRITICAL, response.severity());
        assertEquals(HumanAttentionAlertReason.UNHEALTHY, response.reason());
        assertEquals("human-attention-v1", response.policyVersion());
        assertEquals(openedAt, response.openedAt());
        assertEquals(openedAt, response.lastObservedAt());
        assertNull(response.resolvedAt());
        assertEquals(snapshotId, response.firstSnapshotId());
        assertEquals(snapshotId, response.latestSnapshotId());
        assertEquals(HealthStatus.UNHEALTHY, response.latestHealthStatus());
        assertEquals(30, response.latestHealthScore());
        assertNull(response.latestAssessmentId());
        assertNull(response.modelAttentionSuggested());
    }

    private static HumanAttentionAlertResponse responseFixture() {
        UUID alertId = UUID.fromString("b497184c-d3e1-4fe2-9660-015c89042c95");
        UUID snapshotId = UUID.fromString("267eccca-cd10-4c13-bf20-209ed84f24cd");
        Instant openedAt = Instant.parse("2026-10-12T02:04:12.158846Z");
        HumanAttentionAlert alert = new HumanAttentionAlert(
                alertId,
                HumanAttentionAlertState.OPEN,
                HumanAttentionAlertSeverity.CRITICAL,
                HumanAttentionAlertReason.UNHEALTHY,
                "human-attention-v1",
                openedAt,
                openedAt,
                null,
                snapshotId,
                snapshotId,
                HealthStatus.UNHEALTHY,
                30,
                null,
                null);
        return HumanAttentionAlertResponse.from(alert);
    }
}
