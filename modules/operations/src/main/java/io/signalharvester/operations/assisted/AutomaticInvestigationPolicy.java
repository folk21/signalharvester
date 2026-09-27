package io.signalharvester.operations.assisted;

import io.signalharvester.operations.model.HealthSnapshot;
import io.signalharvester.operations.model.HealthStatus;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Selects event or periodic automatic investigation without performing I/O or model calls. */
@Singleton
public final class AutomaticInvestigationPolicy {

    /** Returns the one trigger type eligible for the latest snapshot, if any. */
    public Optional<AutomaticInvestigationTriggerType> evaluate(
            AutomaticInvestigationMode mode,
            HealthSnapshot current,
            HealthSnapshot previous,
            AutomaticInvestigationTrigger latestTrigger,
            HealthSnapshot latestTriggerSnapshot,
            Instant now,
            Duration cooldown,
            Duration periodicInterval) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(cooldown, "cooldown");
        Objects.requireNonNull(periodicInterval, "periodicInterval");
        if (mode == AutomaticInvestigationMode.OFF || severity(current.overallStatus()) == 0) {
            return Optional.empty();
        }
        if (latestTrigger != null && latestTrigger.active()) {
            return Optional.empty();
        }

        int currentSeverity = severity(current.overallStatus());
        int previousSeverity = previous == null ? 0 : severity(previous.overallStatus());
        boolean worsened = currentSeverity > previousSeverity;
        if (worsened && eventCooldownAllows(current, latestTrigger, latestTriggerSnapshot, now, cooldown)) {
            return Optional.of(AutomaticInvestigationTriggerType.EVENT);
        }

        if (mode != AutomaticInvestigationMode.EVENT_AND_PERIODIC) {
            return Optional.empty();
        }
        if (latestTrigger == null) {
            return Optional.of(AutomaticInvestigationTriggerType.PERIODIC);
        }
        Duration minimumSpacing = cooldown.compareTo(periodicInterval) >= 0 ? cooldown : periodicInterval;
        if (!lastActivityAt(latestTrigger).plus(minimumSpacing).isAfter(now)) {
            return Optional.of(AutomaticInvestigationTriggerType.PERIODIC);
        }
        return Optional.empty();
    }

    private static boolean eventCooldownAllows(
            HealthSnapshot current,
            AutomaticInvestigationTrigger latestTrigger,
            HealthSnapshot latestTriggerSnapshot,
            Instant now,
            Duration cooldown) {
        if (latestTrigger == null) {
            return true;
        }
        if (!lastActivityAt(latestTrigger).plus(cooldown).isAfter(now)) {
            return true;
        }
        return current.overallStatus() == HealthStatus.UNHEALTHY
                && (latestTriggerSnapshot == null || latestTriggerSnapshot.overallStatus() != HealthStatus.UNHEALTHY);
    }

    private static Instant lastActivityAt(AutomaticInvestigationTrigger trigger) {
        return trigger.completedAt() == null ? trigger.createdAt() : trigger.completedAt();
    }

    private static int severity(HealthStatus status) {
        return switch (status) {
            case DEGRADED -> 1;
            case UNHEALTHY -> 2;
            case HEALTHY, UNKNOWN -> 0;
        };
    }
}
