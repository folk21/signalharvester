package io.signalharvester.analysis.application;

import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.analysis.configuration.AnalysisClockFactory;
import io.signalharvester.analysis.event.AnalysisPublicationResult;
import io.signalharvester.analysis.outbox.AnalysisOutbox;
import io.signalharvester.analysis.event.AnalyzedItem;
import io.signalharvester.analysis.event.RejectedItem;
import io.signalharvester.analysis.model.DiscoveredRawItem;
import io.signalharvester.analysis.model.NormalizedContentItem;
import io.signalharvester.analysis.normalization.ContentNormalizer;
import io.signalharvester.analysis.observability.AnalysisObservability;
import io.signalharvester.analysis.persistence.DeduplicationClaimRepository;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes normalization, durable profile-scoped deduplication, deterministic analysis, and outbox staging.
 *
 * <p>The deduplication claim/update and exact serialized terminal event are committed in one PostgreSQL
 * transaction. Kafka delivery happens later through the Analysis outbox dispatcher, removing the database
 * commit versus terminal-event publication gap from this processing path.</p>
 */
@Singleton
public final class RawItemProcessingService implements RawItemProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(RawItemProcessingService.class);
    private static final String DUPLICATE_REASON = "DUPLICATE";
    private static final String DUPLICATE_EXPLANATION =
            "Logical item was already accepted for this monitoring profile";

    private final ContentNormalizer normalizer;
    private final DeduplicationClaimRepository deduplicationRepository;
    private final ContentAnalyzer analyzer;
    private final AnalysisOutbox outbox;
    private final TransactionOperations<Connection> transactions;
    private final AnalysisObservability observability;
    private final Clock clock;

    public RawItemProcessingService(
            ContentNormalizer normalizer,
            DeduplicationClaimRepository deduplicationRepository,
            ContentAnalyzer analyzer,
            AnalysisOutbox outbox,
            @Named("default") TransactionOperations<Connection> transactions,
            AnalysisObservability observability,
            @Named(AnalysisClockFactory.ANALYSIS_CLOCK) Clock clock) {
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
        this.deduplicationRepository = Objects.requireNonNull(deduplicationRepository, "deduplicationRepository");
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.observability = Objects.requireNonNull(observability, "observability");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public RawItemProcessingResult process(DiscoveredRawItem rawItem) {
        Objects.requireNonNull(rawItem, "rawItem");
        long startedAtNanos = System.nanoTime();
        try {
            NormalizedContentItem normalized = normalizer.normalize(rawItem);
            RawItemProcessingResult result = transactions.executeWrite(status -> processInTransaction(normalized));
            observability.recordProcessing(
                    result.status().name(), Duration.ofNanos(System.nanoTime() - startedAtNanos));
            return result;
        } catch (RuntimeException | Error failure) {
            observability.recordProcessing("FAILED_EXCEPTION", Duration.ofNanos(System.nanoTime() - startedAtNanos));
            throw failure;
        }
    }

    private RawItemProcessingResult processInTransaction(NormalizedContentItem item) {
        Instant seenAt = clock.instant();
        if (!deduplicationRepository.tryClaim(item, seenAt)) {
            deduplicationRepository.recordDuplicate(item, seenAt);
            AnalysisPublicationResult publication = outbox.enqueueRejected(
                    new RejectedItem(item, DUPLICATE_REASON, DUPLICATE_EXPLANATION));
            LOG.info(
                    "Rejected duplicate raw item {} normalizedItemId={} profile={} source={}",
                    item.rawItemId(), item.normalizedItemId(), item.monitoringProfileId(), item.sourceId());
            return new RawItemProcessingResult(
                    RawItemProcessingStatus.DUPLICATE,
                    item.normalizedItemId(),
                    publication.eventId(),
                    publication.topic());
        }

        AnalysisDecision decision = analyzer.analyze(item);
        AnalysisPublicationResult publication = outbox.enqueueAnalyzed(new AnalyzedItem(item, decision));
        LOG.info(
                "Analyzed raw item {} normalizedItemId={} profile={} source={} classification={} score={}",
                item.rawItemId(),
                item.normalizedItemId(),
                item.monitoringProfileId(),
                item.sourceId(),
                decision.classification(),
                decision.score());
        return new RawItemProcessingResult(
                RawItemProcessingStatus.ANALYZED,
                item.normalizedItemId(),
                publication.eventId(),
                publication.topic());
    }
}
