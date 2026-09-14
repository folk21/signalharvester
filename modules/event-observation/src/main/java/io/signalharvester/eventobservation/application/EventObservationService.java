package io.signalharvester.eventobservation.application;

import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.eventobservation.configuration.EventObservationRetentionConfiguration;
import io.signalharvester.eventobservation.model.ObservedEvent;
import io.signalharvester.eventobservation.model.ObservedEventInput;
import io.signalharvester.eventobservation.persistence.EventObservationRepository;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;

/** Owns transactions for durable event observation, retention, and bounded reads. */
@Singleton
public final class EventObservationService implements EventObservationRecorder, EventObservationQuery {

    public static final int MIN_LIMIT = 1;
    public static final int MAX_LIMIT = 500;

    private final EventObservationRepository repository;
    private final EventObservationRetentionConfiguration retention;
    private final TransactionOperations<Connection> transactions;

    public EventObservationService(
            EventObservationRepository repository,
            EventObservationRetentionConfiguration retention,
            @Named("default") TransactionOperations<Connection> transactions) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.retention = Objects.requireNonNull(retention, "retention");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        if (retention.getMaxAge().isNegative() || retention.getMaxAge().isZero()) {
            throw new IllegalArgumentException("Event observation maxAge must be positive");
        }
    }

    @Override
    public void record(ObservedEventInput event) {
        Objects.requireNonNull(event, "event");
        transactions.executeWrite(status -> {
            repository.insert(event);
            repository.pruneBefore(event.observedAt().minus(retention.getMaxAge()));
            repository.pruneToMaxEvents(retention.getMaxEvents());
            return null;
        });
    }

    @Override
    public List<ObservedEvent> recent(EventObservationCriteria criteria, int limit) {
        validateLimit(limit);
        Objects.requireNonNull(criteria, "criteria");
        return transactions.executeRead(status -> repository.findRecent(criteria, limit));
    }

    @Override
    public long currentCursor() {
        return transactions.executeRead(status -> repository.currentCursor());
    }

    @Override
    public EventObservationLiveBatch pollAfter(long cursor, EventObservationCriteria criteria, int limit) {
        if (cursor < 0) {
            throw new IllegalArgumentException("cursor must not be negative");
        }
        validateLimit(limit);
        Objects.requireNonNull(criteria, "criteria");
        return transactions.executeRead(status -> {
            long watermark = repository.currentCursor();
            long effectiveCursor = Math.min(cursor, watermark);
            if (effectiveCursor == watermark) {
                return new EventObservationLiveBatch(watermark, List.of());
            }
            List<ObservedEvent> events = repository.findAfter(effectiveCursor, watermark, criteria, limit);
            long nextCursor = events.size() == limit
                    ? events.get(events.size() - 1).observationId()
                    : watermark;
            return new EventObservationLiveBatch(nextCursor, events);
        });
    }

    private static void validateLimit(int limit) {
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between " + MIN_LIMIT + " and " + MAX_LIMIT);
        }
    }
}
