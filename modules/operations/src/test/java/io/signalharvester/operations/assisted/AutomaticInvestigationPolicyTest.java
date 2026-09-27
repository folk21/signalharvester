package io.signalharvester.operations.assisted;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.signalharvester.operations.model.HealthSnapshot;
import io.signalharvester.operations.model.HealthStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AutomaticInvestigationPolicyTest {
    private static final Instant NOW = Instant.parse("2026-09-25T12:00:00Z");
    private static final Duration COOLDOWN = Duration.ofMinutes(15);
    private static final Duration PERIODIC = Duration.ofMinutes(30);

    private final AutomaticInvestigationPolicy policy = new AutomaticInvestigationPolicy();

    @Test
    void shouldTriggerOnWorseningHealthAndAllowUnhealthyEscalationInsideCooldown() {
        HealthSnapshot healthy = snapshot(HealthStatus.HEALTHY, NOW.minusSeconds(60));
        HealthSnapshot degraded = snapshot(HealthStatus.DEGRADED, NOW);

        assertEquals(
                Optional.of(AutomaticInvestigationTriggerType.EVENT),
                policy.evaluate(
                        AutomaticInvestigationMode.EVENT,
                        degraded,
                        healthy,
                        null,
                        null,
                        NOW,
                        COOLDOWN,
                        PERIODIC));

        AutomaticInvestigationTrigger recentDegradedTrigger = trigger(
                degraded.id(), NOW.minusSeconds(60), AutomaticInvestigationTriggerState.SUCCEEDED);
        HealthSnapshot unhealthy = snapshot(HealthStatus.UNHEALTHY, NOW.plusSeconds(60));

        assertEquals(
                Optional.of(AutomaticInvestigationTriggerType.EVENT),
                policy.evaluate(
                        AutomaticInvestigationMode.EVENT,
                        unhealthy,
                        degraded,
                        recentDegradedTrigger,
                        degraded,
                        NOW.plusSeconds(60),
                        COOLDOWN,
                        PERIODIC));
    }

    @Test
    void shouldSuppressComparableEventsDuringCooldownAndActiveWork() {
        HealthSnapshot healthy = snapshot(HealthStatus.HEALTHY, NOW.minusSeconds(60));
        HealthSnapshot degraded = snapshot(HealthStatus.DEGRADED, NOW);
        AutomaticInvestigationTrigger recent = trigger(
                UUID.randomUUID(), NOW.minusSeconds(30), AutomaticInvestigationTriggerState.SUCCEEDED);

        assertTrue(policy.evaluate(
                AutomaticInvestigationMode.EVENT,
                degraded,
                healthy,
                recent,
                degraded,
                NOW,
                COOLDOWN,
                PERIODIC).isEmpty());

        AutomaticInvestigationTrigger active = trigger(
                UUID.randomUUID(), NOW.minusSeconds(3600), AutomaticInvestigationTriggerState.CLAIMED);
        assertTrue(policy.evaluate(
                AutomaticInvestigationMode.EVENT_AND_PERIODIC,
                degraded,
                degraded,
                active,
                degraded,
                NOW,
                COOLDOWN,
                PERIODIC).isEmpty());
    }

    @Test
    void shouldPeriodicallyReassessStableActiveIncidentAfterConfiguredInterval() {
        HealthSnapshot unhealthy = snapshot(HealthStatus.UNHEALTHY, NOW);
        AutomaticInvestigationTrigger old = trigger(
                UUID.randomUUID(), NOW.minus(Duration.ofMinutes(31)), AutomaticInvestigationTriggerState.SUCCEEDED);

        assertEquals(
                Optional.of(AutomaticInvestigationTriggerType.PERIODIC),
                policy.evaluate(
                        AutomaticInvestigationMode.EVENT_AND_PERIODIC,
                        unhealthy,
                        unhealthy,
                        old,
                        unhealthy,
                        NOW,
                        COOLDOWN,
                        PERIODIC));

        assertTrue(policy.evaluate(
                AutomaticInvestigationMode.EVENT,
                unhealthy,
                unhealthy,
                old,
                unhealthy,
                NOW,
                COOLDOWN,
                PERIODIC).isEmpty());
    }


    @Test
    void shouldMeasurePeriodicSpacingFromTerminalCompletionRatherThanTriggerCreation() {
        HealthSnapshot unhealthy = snapshot(HealthStatus.UNHEALTHY, NOW);
        AutomaticInvestigationTrigger recentlyCompleted = new AutomaticInvestigationTrigger(
                UUID.randomUUID(),
                unhealthy.id(),
                AutomaticInvestigationTriggerType.EVENT,
                AutomaticInvestigationTriggerState.SUCCEEDED,
                NOW.minus(Duration.ofHours(2)),
                NOW.minus(Duration.ofHours(2)),
                2,
                NOW.minus(Duration.ofMinutes(5)),
                null,
                null);

        assertTrue(policy.evaluate(
                AutomaticInvestigationMode.EVENT_AND_PERIODIC,
                unhealthy,
                unhealthy,
                recentlyCompleted,
                unhealthy,
                NOW,
                COOLDOWN,
                PERIODIC).isEmpty());
    }

    @Test
    void shouldNeverAutomaticallyInvestigateHealthyOrUnknownSnapshots() {
        assertTrue(policy.evaluate(
                AutomaticInvestigationMode.EVENT_AND_PERIODIC,
                snapshot(HealthStatus.HEALTHY, NOW),
                null,
                null,
                null,
                NOW,
                COOLDOWN,
                PERIODIC).isEmpty());
        assertTrue(policy.evaluate(
                AutomaticInvestigationMode.EVENT_AND_PERIODIC,
                snapshot(HealthStatus.UNKNOWN, NOW),
                null,
                null,
                null,
                NOW,
                COOLDOWN,
                PERIODIC).isEmpty());
    }

    private static HealthSnapshot snapshot(HealthStatus status, Instant generatedAt) {
        return new HealthSnapshot(
                UUID.randomUUID(),
                generatedAt,
                generatedAt.minusSeconds(300),
                generatedAt,
                status,
                status == HealthStatus.HEALTHY ? 100 : 60,
                "test-policy",
                Map.of("operations", status.name()),
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                "test",
                true,
                List.of());
    }

    private static AutomaticInvestigationTrigger trigger(
            UUID snapshotId, Instant createdAt, AutomaticInvestigationTriggerState state) {
        return new AutomaticInvestigationTrigger(
                UUID.randomUUID(),
                snapshotId,
                AutomaticInvestigationTriggerType.EVENT,
                state,
                createdAt,
                createdAt,
                1,
                createdAt,
                null,
                null);
    }
}
