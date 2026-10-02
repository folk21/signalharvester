package io.signalharvester.operations.alert;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.signalharvester.operations.model.HealthSnapshot;
import io.signalharvester.operations.model.HealthStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AlertDecisionPolicyTest {
    private final AlertDecisionPolicy policy = new AlertDecisionPolicy();

    @Test
    void shouldRequirePersistentDegradationButEscalateUnhealthyImmediately() {
        Instant now = Instant.parse("2026-09-29T12:00:00Z");
        var firstDegraded = snapshot(HealthStatus.DEGRADED, now.minusSeconds(60));
        var secondDegraded = snapshot(HealthStatus.DEGRADED, now);

        assertInstanceOf(AlertDecision.None.class, policy.evaluate(
                firstDegraded, List.of(), null, null, now.minusSeconds(60), 2, 1, 2, Duration.ofMinutes(15)));
        assertInstanceOf(AlertDecision.Open.class, policy.evaluate(
                secondDegraded, List.of(firstDegraded), null, null, now, 2, 1, 2, Duration.ofMinutes(15)));
        assertInstanceOf(AlertDecision.Open.class, policy.evaluate(
                snapshot(HealthStatus.UNHEALTHY, now), List.of(), null, null, now, 2, 1, 2, Duration.ofMinutes(15)));
    }

    @Test
    void shouldRequireSustainedRecoveryAndApplyCooldownOnlyToDegradedReopen() {
        Instant now = Instant.parse("2026-09-29T12:00:00Z");
        HumanAttentionAlert active = alert(HumanAttentionAlertState.OPEN, HumanAttentionAlertSeverity.CRITICAL, null, now.minusSeconds(300));
        var previousHealthy = snapshot(HealthStatus.HEALTHY, now.minusSeconds(60));
        var currentHealthy = snapshot(HealthStatus.HEALTHY, now);
        assertInstanceOf(AlertDecision.Resolve.class, policy.evaluate(
                currentHealthy, List.of(previousHealthy), active, null, now, 2, 1, 2, Duration.ofMinutes(15)));

        HumanAttentionAlert resolved = alert(HumanAttentionAlertState.RESOLVED, HumanAttentionAlertSeverity.WARNING, now.minusSeconds(60), now.minusSeconds(600));
        var previousDegraded = snapshot(HealthStatus.DEGRADED, now.minusSeconds(30));
        var currentDegraded = snapshot(HealthStatus.DEGRADED, now);
        assertInstanceOf(AlertDecision.None.class, policy.evaluate(
                currentDegraded, List.of(previousDegraded), null, resolved, now, 2, 1, 2, Duration.ofMinutes(15)));
        assertInstanceOf(AlertDecision.Open.class, policy.evaluate(
                snapshot(HealthStatus.UNHEALTHY, now), List.of(), null, resolved, now, 2, 1, 2, Duration.ofMinutes(15)));
    }

    private static HealthSnapshot snapshot(HealthStatus status, Instant generatedAt) {
        return new HealthSnapshot(
                UUID.randomUUID(), generatedAt, generatedAt.minusSeconds(300), generatedAt, status,
                status == HealthStatus.HEALTHY ? 100 : 60, "deterministic-statistical-v1",
                Map.of(), Map.of(), List.of(), List.of(), List.of(), "test", true, List.of());
    }

    private static HumanAttentionAlert alert(
            HumanAttentionAlertState state,
            HumanAttentionAlertSeverity severity,
            Instant resolvedAt,
            Instant openedAt) {
        return new HumanAttentionAlert(
                UUID.randomUUID(), state, severity, HumanAttentionAlertReason.UNHEALTHY, "human-attention-v1",
                openedAt, resolvedAt == null ? openedAt : resolvedAt, resolvedAt, UUID.randomUUID(), UUID.randomUUID(),
                state == HumanAttentionAlertState.RESOLVED ? HealthStatus.HEALTHY : HealthStatus.UNHEALTHY,
                state == HumanAttentionAlertState.RESOLVED ? 100 : 30, null, null);
    }
}
