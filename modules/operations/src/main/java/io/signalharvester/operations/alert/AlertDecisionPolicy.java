package io.signalharvester.operations.alert;

import io.signalharvester.operations.model.HealthSnapshot;
import io.signalharvester.operations.model.HealthStatus;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Deterministic policy that owns alert eligibility, severity, persistence, resolution, and reopen cooldown. */
@Singleton
public final class AlertDecisionPolicy {

    public AlertDecision evaluate(
            HealthSnapshot current,
            List<HealthSnapshot> recentNewestFirst,
            HumanAttentionAlert active,
            HumanAttentionAlert latestResolved,
            Instant now,
            int degradedThreshold,
            int unhealthyThreshold,
            int healthyResolveThreshold,
            Duration reopenCooldown) {
        Objects.requireNonNull(current, "current");
        recentNewestFirst = List.copyOf(Objects.requireNonNull(recentNewestFirst, "recentNewestFirst"));
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(reopenCooldown, "reopenCooldown");
        if (degradedThreshold < 1 || unhealthyThreshold < 1 || healthyResolveThreshold < 1) {
            throw new IllegalArgumentException("alert snapshot thresholds must be positive");
        }
        if (reopenCooldown.isNegative()) {
            throw new IllegalArgumentException("reopenCooldown must not be negative");
        }

        int unhealthyCount = consecutive(current, recentNewestFirst, HealthStatus.UNHEALTHY);
        int degradedCount = consecutive(current, recentNewestFirst, HealthStatus.DEGRADED);
        int healthyCount = consecutive(current, recentNewestFirst, HealthStatus.HEALTHY);

        if (active != null) {
            if (healthyCount >= healthyResolveThreshold) {
                return new AlertDecision.Resolve();
            }
            if (unhealthyCount >= unhealthyThreshold) {
                return new AlertDecision.Update(HumanAttentionAlertSeverity.CRITICAL, HumanAttentionAlertReason.UNHEALTHY);
            }
            if (active.severity() == HumanAttentionAlertSeverity.CRITICAL) {
                return new AlertDecision.Update(active.severity(), active.reason());
            }
            return new AlertDecision.Update(HumanAttentionAlertSeverity.WARNING, HumanAttentionAlertReason.SUSTAINED_DEGRADED);
        }

        if (unhealthyCount >= unhealthyThreshold) {
            return new AlertDecision.Open(HumanAttentionAlertSeverity.CRITICAL, HumanAttentionAlertReason.UNHEALTHY);
        }
        if (degradedCount >= degradedThreshold && outsideDegradedReopenCooldown(latestResolved, now, reopenCooldown)) {
            return new AlertDecision.Open(HumanAttentionAlertSeverity.WARNING, HumanAttentionAlertReason.SUSTAINED_DEGRADED);
        }
        return new AlertDecision.None();
    }

    private static int consecutive(
            HealthSnapshot current, List<HealthSnapshot> previousNewestFirst, HealthStatus status) {
        if (current.overallStatus() != status) {
            return 0;
        }
        int count = 1;
        for (HealthSnapshot snapshot : previousNewestFirst) {
            if (snapshot.overallStatus() != status) {
                break;
            }
            count++;
        }
        return count;
    }

    private static boolean outsideDegradedReopenCooldown(
            HumanAttentionAlert latestResolved, Instant now, Duration reopenCooldown) {
        if (latestResolved == null || latestResolved.resolvedAt() == null || reopenCooldown.isZero()) {
            return true;
        }
        return !latestResolved.resolvedAt().plus(reopenCooldown).isAfter(now);
    }
}
