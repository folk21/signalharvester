package io.signalharvester.collection.scheduling;

import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.configuration.api.ConfiguredMonitoringProfile;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Owns short database transactions for cluster-safe schedule claim, heartbeat, pre-run release, and completion. */
@Singleton
public final class ProfileScheduleCoordinator {
    private final ProfileScheduleStateStore store;
    private final TransactionOperations<Connection> transactions;

    public ProfileScheduleCoordinator(
            ProfileScheduleStateStore store,
            @Named("default") TransactionOperations<Connection> transactions) {
        this.store = Objects.requireNonNull(store, "store");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    /** Attempts to claim one profile if its persisted schedule is due. */
    public Optional<ProfileScheduleLease> claim(
            ConfiguredMonitoringProfile profile, Instant now, Duration leaseDuration) {
        Objects.requireNonNull(profile, "profile");
        return transactions.executeWrite(status -> store.claimIfDue(
                profile.id(), profile.collectionIntervalMinutes(), now, leaseDuration));
    }

    /** Renews an active lease without holding a transaction across collection work. */
    public boolean renew(ProfileScheduleLease lease, Instant now, Duration leaseDuration) {
        return transactions.executeWrite(status -> store.renew(lease, now, leaseDuration));
    }

    /** Releases an active lease without advancing the persisted next-due time. */
    public boolean release(ProfileScheduleLease lease, Instant releasedAt) {
        return transactions.executeWrite(status -> store.release(lease, releasedAt));
    }

    /** Releases an active lease and schedules the next run after the configured interval. */
    public boolean complete(ProfileScheduleLease lease, Instant completedAt) {
        return transactions.executeWrite(status -> store.complete(lease, completedAt));
    }
}
