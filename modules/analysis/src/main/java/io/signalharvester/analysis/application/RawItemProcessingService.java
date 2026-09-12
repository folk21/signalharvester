package io.signalharvester.analysis.application;

import io.micronaut.transaction.TransactionOperations;
import io.signalharvester.analysis.configuration.AnalysisClockFactory;
import io.signalharvester.analysis.event.AnalysisEventPublisher;
import io.signalharvester.analysis.event.AnalysisPublicationResult;
import io.signalharvester.analysis.event.AnalyzedItem;
import io.signalharvester.analysis.event.RejectedItem;
import io.signalharvester.analysis.model.DiscoveredRawItem;
import io.signalharvester.analysis.model.NormalizedContentItem;
import io.signalharvester.analysis.normalization.ContentNormalizer;
import io.signalharvester.analysis.persistence.DeduplicationClaimRepository;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes normalization, durable profile-scoped deduplication, deterministic analysis, and publication.
 *
 * <p>The deduplication claim and terminal event publication intentionally share the caller-owned JDBC
 * transaction window. If publication fails, the claim/update rolls back and Kafka redelivery can retry.
 * A broker acknowledgement followed by a database commit failure can still duplicate the analysis event;
 * downstream persistence must therefore remain idempotent until an outbox/stronger consistency mechanism
 * is introduced.</p>
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
    private final AnalysisEventPublisher eventPublisher;
    private final TransactionOperations<Connection> transactions;
    private final Clock clock;

    public RawItemProcessingService(
            ContentNormalizer normalizer,
            DeduplicationClaimRepository deduplicationRepository,
            ContentAnalyzer analyzer,
            AnalysisEventPublisher eventPublisher,
            @Named("default") TransactionOperations<Connection> transactions,
            @Named(AnalysisClockFactory.ANALYSIS_CLOCK) Clock clock) {
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
        this.deduplicationRepository = Objects.requireNonNull(deduplicationRepository, "deduplicationRepository");
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public RawItemProcessingResult process(DiscoveredRawItem rawItem) {
        Objects.requireNonNull(rawItem, "rawItem");
        NormalizedContentItem normalized = normalizer.normalize(rawItem);
        return transactions.executeWrite(status -> processInTransaction(normalized));
    }

    private RawItemProcessingResult processInTransaction(NormalizedContentItem item) {
        Instant seenAt = clock.instant();
        if (!deduplicationRepository.tryClaim(item, seenAt)) {
            deduplicationRepository.recordDuplicate(item, seenAt);
            AnalysisPublicationResult publication = eventPublisher.publishRejected(
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
        AnalysisPublicationResult publication = eventPublisher.publishAnalyzed(new AnalyzedItem(item, decision));
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
