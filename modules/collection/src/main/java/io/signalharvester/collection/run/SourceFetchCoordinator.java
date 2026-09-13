package io.signalharvester.collection.run;

import io.micronaut.scheduling.TaskExecutors;
import io.signalharvester.collection.configuration.CollectionConfiguration;
import io.signalharvester.collection.source.ExternalSourceClient;
import io.signalharvester.collection.source.FetchedSourceContent;
import io.signalharvester.collection.source.SourceFetchException;
import io.signalharvester.configuration.api.ConfiguredSource;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;

/**
 * Coordinates bounded best-effort external-source fetching on Micronaut's blocking executor.
 *
 * <p>On the Java 21 project baseline Micronaut's blocking executor uses Virtual Threads. The
 * coordinator keeps at most the configured number of source fetches/results in flight and applies
 * backpressure by handling one completed outcome before submitting replacement work. Expected
 * per-source fetch failures do not cancel unrelated work. It does not own or shut down the injected
 * executor.</p>
 */
@Singleton
public final class SourceFetchCoordinator {

    private final ExternalSourceClient sourceClient;
    private final CollectionConfiguration configuration;
    private final ExecutorService blockingExecutor;

    public SourceFetchCoordinator(
            ExternalSourceClient sourceClient,
            CollectionConfiguration configuration,
            @Named(TaskExecutors.BLOCKING) ExecutorService blockingExecutor) {
        this.sourceClient = Objects.requireNonNull(sourceClient, "sourceClient");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.blockingExecutor = Objects.requireNonNull(blockingExecutor, "blockingExecutor");
    }

    /**
     * Fetches sources with bounded concurrency and hands each completed outcome to the caller.
     *
     * <p>The handler runs synchronously on the calling thread. A replacement fetch is submitted only
     * after the handler returns, so completed payloads cannot accumulate beyond the configured
     * concurrency window. The handler receives the original source index so callers can preserve
     * deterministic result ordering even though outcomes are delivered in completion order.</p>
     *
     * <p>{@link SourceFetchException} failures become source outcomes and do not cancel peer work. A
     * coordinator interruption, unexpected fetch failure, or unexpected handler failure aborts the
     * operation and cancels remaining in-flight fetches.</p>
     *
     * @param sources source configurations in desired final result order
     * @param outcomeHandler terminal handler for one completed source fetch
     */
    void fetchEach(
            List<ConfiguredSource> sources,
            BiConsumer<Integer, SourceFetchOutcome> outcomeHandler) {
        List<ConfiguredSource> snapshot = List.copyOf(Objects.requireNonNull(sources, "sources"));
        Objects.requireNonNull(outcomeHandler, "outcomeHandler");
        if (snapshot.isEmpty()) {
            return;
        }

        int maxInFlight = Math.min(configuration.getMaxConcurrency(), snapshot.size());
        ExecutorCompletionService<IndexedSourceFetchOutcome> completions =
                new ExecutorCompletionService<>(blockingExecutor);
        Set<Future<IndexedSourceFetchOutcome>> inFlight = new HashSet<>(maxInFlight);
        int nextIndex = 0;

        for (; nextIndex < maxInFlight; nextIndex++) {
            inFlight.add(submitFetch(completions, snapshot, nextIndex));
        }

        try {
            int handled = 0;
            while (handled < snapshot.size()) {
                Future<IndexedSourceFetchOutcome> completedFuture = completions.take();
                inFlight.remove(completedFuture);
                IndexedSourceFetchOutcome completed = completedFuture.get();

                outcomeHandler.accept(completed.index(), completed.outcome());
                handled++;

                if (nextIndex < snapshot.size()) {
                    inFlight.add(submitFetch(completions, snapshot, nextIndex));
                    nextIndex++;
                }
            }
        } catch (InterruptedException interrupted) {
            cancel(inFlight);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Source fetch coordination was interrupted", interrupted);
        } catch (ExecutionException failedFetch) {
            cancel(inFlight);
            rethrowUnexpectedFailure(failedFetch.getCause());
        } catch (RuntimeException | Error failedHandler) {
            cancel(inFlight);
            throw failedHandler;
        }
    }

    private Future<IndexedSourceFetchOutcome> submitFetch(
            ExecutorCompletionService<IndexedSourceFetchOutcome> completions,
            List<ConfiguredSource> sources,
            int index) {
        return completions.submit(() -> new IndexedSourceFetchOutcome(index, fetch(sources.get(index))));
    }

    private SourceFetchOutcome fetch(ConfiguredSource source) {
        try {
            FetchedSourceContent content = Objects.requireNonNull(
                    sourceClient.fetch(source),
                    "ExternalSourceClient returned null content");
            return new SourceFetchOutcome.Success(source, content);
        } catch (SourceFetchException failure) {
            return new SourceFetchOutcome.Failure(source, failure);
        }
    }

    private static void cancel(Set<? extends Future<?>> inFlight) {
        inFlight.forEach(future -> future.cancel(true));
    }

    private static void rethrowUnexpectedFailure(Throwable cause) {
        if (cause instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Source fetch worker failed", cause);
    }

    private record IndexedSourceFetchOutcome(int index, SourceFetchOutcome outcome) {
        private IndexedSourceFetchOutcome {
            if (index < 0) {
                throw new IllegalArgumentException("index must be non-negative");
            }
            Objects.requireNonNull(outcome, "outcome");
        }
    }
}
