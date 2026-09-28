package io.signalharvester.operations.application;

import io.micronaut.context.annotation.Value;
import io.micronaut.transaction.TransactionOperations;
import io.opentelemetry.api.trace.Span;
import io.signalharvester.operations.api.OperationalChangeJournal;
import io.signalharvester.operations.api.OperationalChangeRecord;
import io.signalharvester.operations.api.OperationalChangeRecordingException;
import io.signalharvester.operations.api.OperationalChangeRequest;
import io.signalharvester.operations.persistence.OperationalIntelligenceRepository;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Persists sanitized operational change records and fills runtime-owned trace/version metadata. */
@Singleton
public final class OperationalChangeJournalService implements OperationalChangeJournal {
    private final OperationalIntelligenceRepository repository;
    private final TransactionOperations<Connection> transactions;
    private final Clock clock;
    private final String applicationVersion;

    public OperationalChangeJournalService(
            OperationalIntelligenceRepository repository,
            @Named("default") TransactionOperations<Connection> transactions,
            @Value("${signalharvester.build.version:dev}") String applicationVersion) {
        this(repository, transactions, Clock.systemUTC(), applicationVersion);
    }

    OperationalChangeJournalService(
            OperationalIntelligenceRepository repository,
            TransactionOperations<Connection> transactions,
            Clock clock,
            String applicationVersion) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.applicationVersion = requireText(applicationVersion, "applicationVersion");
    }

    @Override
    public OperationalChangeRecord record(OperationalChangeRequest request) {
        Objects.requireNonNull(request, "request");
        if (!request.context().journalEnabled()) {
            throw new IllegalArgumentException("journal-disabled context cannot be recorded");
        }
        try {
            return transactions.executeWrite(status -> recordInCurrentTransaction(request));
        } catch (RuntimeException failure) {
            throw wrapRecordingFailure(failure);
        }
    }

    @Override
    public OperationalChangeRecord recordInCurrentTransaction(OperationalChangeRequest request) {
        Objects.requireNonNull(request, "request");
        if (!request.context().journalEnabled()) {
            throw new IllegalArgumentException("journal-disabled context cannot be recorded");
        }
        OperationalChangeRecord change = new OperationalChangeRecord(
                UUID.randomUUID(),
                clock.instant(),
                request.category(),
                request.targetType(),
                request.targetId(),
                request.beforeState(),
                request.afterState(),
                request.outcome(),
                request.context().source(),
                request.context().actorId(),
                request.context().correlationId(),
                currentTraceId(),
                applicationVersion);
        try {
            repository.insertChange(change);
            return change;
        } catch (RuntimeException failure) {
            throw wrapRecordingFailure(failure);
        }
    }

    private static OperationalChangeRecordingException wrapRecordingFailure(RuntimeException failure) {
        if (failure instanceof OperationalChangeRecordingException recordingFailure) {
            return recordingFailure;
        }
        return new OperationalChangeRecordingException("Failed to persist operational change", failure);
    }

    private static String currentTraceId() {
        var context = Span.current().getSpanContext();
        return context.isValid() ? context.getTraceId() : "";
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return trimmed;
    }
}
