package io.signalharvester.analysis.outbox;

import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.analysis.configuration.AnalysisClockFactory;
import io.signalharvester.analysis.configuration.AnalysisOutboxConfiguration;
import io.signalharvester.analysis.event.kafka.AnalysisKafkaClient;
import io.signalharvester.analysis.observability.AnalysisObservability;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.kafka.common.errors.InterruptException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Publishes committed Analysis outbox records with short PostgreSQL leases and idempotent event identity. */
@Singleton
@Requires(property = "signalharvester.analysis.enabled", notEquals = "false", defaultValue = "true")
@Requires(property = "signalharvester.analysis.outbox.enabled", notEquals = "false", defaultValue = "true")
@Requires(property = "kafka.enabled", notEquals = "false", defaultValue = "true")
public final class AnalysisOutboxDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(AnalysisOutboxDispatcher.class);
    private static final int MAX_ERROR_CHARS = 1000;
    private static final Duration MAX_LEASE_DURATION = Duration.ofMinutes(5);
    private static final Duration MAX_RETRY_BACKOFF = Duration.ofMinutes(1);

    private final AnalysisOutboxStore store;
    private final AnalysisKafkaClient kafkaClient;
    private final AnalysisOutboxConfiguration configuration;
    private final TransactionOperations<Connection> transactions;
    private final AnalysisObservability observability;
    private final Clock clock;

    public AnalysisOutboxDispatcher(
            AnalysisOutboxStore store,
            AnalysisKafkaClient kafkaClient,
            AnalysisOutboxConfiguration configuration,
            @Named("default") TransactionOperations<Connection> transactions,
            AnalysisObservability observability,
            @Named(AnalysisClockFactory.ANALYSIS_CLOCK) Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.kafkaClient = Objects.requireNonNull(kafkaClient, "kafkaClient");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.observability = Objects.requireNonNull(observability, "observability");
        this.clock = Objects.requireNonNull(clock, "clock");
        requireDuration(configuration.getPollInterval(), Duration.ofMillis(10), Duration.ofMinutes(1), "pollInterval");
        requireDuration(configuration.getLeaseDuration(), Duration.ofMillis(1), MAX_LEASE_DURATION, "leaseDuration");
        requireDuration(configuration.getRetryBackoff(), Duration.ZERO, MAX_RETRY_BACKOFF, "retryBackoff");
    }

    /** Claims one bounded batch and publishes each record outside the database transaction. */
    @Scheduled(fixedDelay = "${signalharvester.analysis.outbox.poll-interval:1s}")
    public void dispatchAvailable() {
        throwIfInterrupted("Interrupted before dispatching Analysis outbox events");
        Instant now = clock.instant();
        UUID leaseToken = UUID.randomUUID();
        List<AnalysisOutboxEntry> entries = measureDatabaseOperation(
                "claim",
                () -> transactions.executeWrite(status -> store.claimBatch(
                        now,
                        leaseToken,
                        now.plus(configuration.getLeaseDuration()),
                        configuration.getBatchSize())));
        if (entries.isEmpty()) {
            return;
        }
        long batchStarted = System.nanoTime();
        try {
            for (AnalysisOutboxEntry entry : entries) {
                throwIfInterrupted("Interrupted while dispatching Analysis outbox events");
                publishOne(entry, leaseToken);
            }
        } finally {
            observability.recordOutboxBatch(entries.size(), elapsed(batchStarted));
        }
    }

    private void publishOne(AnalysisOutboxEntry entry, UUID leaseToken) {
        if (!renewLeaseBeforePublication(entry, leaseToken)) {
            return;
        }
        try {
            publishToKafka(entry);
            Instant publishedAt = clock.instant();
            measureDatabaseOperation("mark_published", () -> transactions.executeWrite(status -> {
                store.markPublished(entry.eventId(), leaseToken, publishedAt);
                return null;
            }));
            observability.recordOutboxPublication("published");
            LOG.debug(
                    "Published Analysis outbox event {} topic={} attempts={}",
                    entry.eventId(), entry.topic(), entry.publicationAttempts());
        } catch (RuntimeException failure) {
            propagateIfInterrupted("Interrupted while publishing Analysis outbox event", failure);
            Instant failedAt = clock.instant();
            Instant nextAttemptAt = failedAt.plus(configuration.getRetryBackoff());
            String failureMessage = boundedMessage(failure);
            try {
                measureDatabaseOperation("mark_failed", () -> transactions.executeWrite(status -> {
                    store.markFailed(entry.eventId(), leaseToken, failedAt, nextAttemptAt, failureMessage);
                    return null;
                }));
            } catch (RuntimeException persistenceFailure) {
                failure.addSuppressed(persistenceFailure);
                propagateIfInterrupted(
                        "Interrupted while recording Analysis outbox publication failure", persistenceFailure);
            }
            observability.recordOutboxPublication("failed");
            LOG.warn(
                    "Analysis outbox publication failed eventId={} topic={} attempts={}; event remains pending",
                    entry.eventId(), entry.topic(), entry.publicationAttempts(), failure);
        }
    }

    private boolean renewLeaseBeforePublication(AnalysisOutboxEntry entry, UUID leaseToken) {
        Instant renewedAt = clock.instant();
        Instant leaseExpiresAt = renewedAt.plus(configuration.getLeaseDuration());
        try {
            measureDatabaseOperation("renew_lease", () -> transactions.executeWrite(status -> {
                store.renewLease(entry.eventId(), leaseToken, renewedAt, leaseExpiresAt);
                return null;
            }));
            return true;
        } catch (RuntimeException failure) {
            propagateIfInterrupted("Interrupted while renewing Analysis outbox lease", failure);
            observability.recordOutboxPublication("failed");
            LOG.warn(
                    "Skipping Analysis outbox publication because lease renewal failed eventId={} topic={} attempts={}",
                    entry.eventId(), entry.topic(), entry.publicationAttempts(), failure);
            return false;
        }
    }

    private void publishToKafka(AnalysisOutboxEntry entry) {
        long started = System.nanoTime();
        try {
            observability.withTraceparent(
                    entry.traceparent(),
                    () -> kafkaClient.send(entry.topic(), entry.eventKey(), entry.payload()));
            observability.recordOutboxKafkaPublish("success", elapsed(started));
        } catch (RuntimeException failure) {
            observability.recordOutboxKafkaPublish("failed", elapsed(started));
            throw failure;
        }
    }

    private <T> T measureDatabaseOperation(String operation, Supplier<T> action) {
        long started = System.nanoTime();
        try {
            T result = action.get();
            observability.recordOutboxDatabaseOperation(operation, "success", elapsed(started));
            return result;
        } catch (RuntimeException failure) {
            observability.recordOutboxDatabaseOperation(operation, "failed", elapsed(started));
            throw failure;
        }
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - started));
    }

    private static void throwIfInterrupted(String message) {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException(message);
        }
    }

    private static void propagateIfInterrupted(String message, RuntimeException failure) {
        if (!Thread.currentThread().isInterrupted() && !hasInterruptedCause(failure)) {
            return;
        }
        Thread.currentThread().interrupt();
        throw new IllegalStateException(message, failure);
    }

    private static boolean hasInterruptedCause(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof InterruptedException || current instanceof InterruptException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String boundedMessage(RuntimeException failure) {
        String message = failure.getMessage();
        String value = failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        return value.length() <= MAX_ERROR_CHARS ? value : value.substring(0, MAX_ERROR_CHARS);
    }

    private static void requireDuration(Duration value, Duration minimum, Duration maximum, String name) {
        Objects.requireNonNull(value, name);
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }
}
