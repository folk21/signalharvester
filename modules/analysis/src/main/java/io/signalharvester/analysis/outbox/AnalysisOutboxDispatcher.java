package io.signalharvester.analysis.outbox;

import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.analysis.configuration.AnalysisClockFactory;
import io.signalharvester.analysis.configuration.AnalysisOutboxConfiguration;
import io.signalharvester.analysis.event.kafka.AnalysisKafkaClient;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
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
    private final Clock clock;

    public AnalysisOutboxDispatcher(
            AnalysisOutboxStore store,
            AnalysisKafkaClient kafkaClient,
            AnalysisOutboxConfiguration configuration,
            @Named("default") TransactionOperations<Connection> transactions,
            @Named(AnalysisClockFactory.ANALYSIS_CLOCK) Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.kafkaClient = Objects.requireNonNull(kafkaClient, "kafkaClient");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        requireDuration(configuration.getPollInterval(), Duration.ofMillis(10), Duration.ofMinutes(1), "pollInterval");
        requireDuration(configuration.getLeaseDuration(), Duration.ofMillis(1), MAX_LEASE_DURATION, "leaseDuration");
        requireDuration(configuration.getRetryBackoff(), Duration.ZERO, MAX_RETRY_BACKOFF, "retryBackoff");
    }

    /** Claims one bounded batch and publishes each record outside the database transaction. */
    @Scheduled(fixedDelay = "${signalharvester.analysis.outbox.poll-interval:1s}")
    public void dispatchAvailable() {
        Instant now = clock.instant();
        UUID leaseToken = UUID.randomUUID();
        List<AnalysisOutboxEntry> entries = transactions.executeWrite(status -> store.claimBatch(
                now,
                leaseToken,
                now.plus(configuration.getLeaseDuration()),
                configuration.getBatchSize()));
        for (AnalysisOutboxEntry entry : entries) {
            publishOne(entry, leaseToken);
        }
    }

    private void publishOne(AnalysisOutboxEntry entry, UUID leaseToken) {
        try {
            kafkaClient.send(entry.topic(), entry.eventKey(), entry.payload());
            Instant publishedAt = clock.instant();
            transactions.executeWrite(status -> {
                store.markPublished(entry.eventId(), leaseToken, publishedAt);
                return null;
            });
            LOG.debug(
                    "Published Analysis outbox event {} topic={} attempts={}",
                    entry.eventId(), entry.topic(), entry.publicationAttempts());
        } catch (RuntimeException failure) {
            Instant nextAttemptAt = clock.instant().plus(configuration.getRetryBackoff());
            String failureMessage = boundedMessage(failure);
            try {
                transactions.executeWrite(status -> {
                    store.markFailed(entry.eventId(), leaseToken, nextAttemptAt, failureMessage);
                    return null;
                });
            } catch (RuntimeException persistenceFailure) {
                failure.addSuppressed(persistenceFailure);
            }
            LOG.warn(
                    "Analysis outbox publication failed eventId={} topic={} attempts={}; event remains pending",
                    entry.eventId(), entry.topic(), entry.publicationAttempts(), failure);
        }
    }

    private static String boundedMessage(RuntimeException failure) {
        String message = failure.getMessage();
        String value = failure.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
        return value.length() <= MAX_ERROR_CHARS ? value : value.substring(0, MAX_ERROR_CHARS);
    }

    private static void requireDuration(Duration value, Duration minimum, Duration maximum, String name) {
        Objects.requireNonNull(value, name);
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }
}
