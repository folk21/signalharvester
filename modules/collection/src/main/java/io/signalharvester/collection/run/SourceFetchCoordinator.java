package io.signalharvester.collection.run;

import io.micronaut.scheduling.TaskExecutors;
import io.signalharvester.collection.configuration.CollectionConfiguration;
import io.signalharvester.collection.source.ExternalSourceClient;
import io.signalharvester.collection.source.FetchedSourceContent;
import io.signalharvester.collection.source.SourceFetchException;
import io.signalharvester.configuration.api.ConfiguredSource;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Coordinates bounded best-effort external-source fetching on Micronaut's blocking executor.
 *
 * <p>On the Java 21 project baseline Micronaut's blocking executor uses Virtual Threads. The
 * coordinator starts only the configured number of workers, preserves source ordering, and records
 * expected per-source fetch failures without cancelling unrelated work. It does not own or shut down the
 * injected executor.</p>
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
     * Fetches all supplied sources with bounded concurrency and deterministic outcome ordering.
     *
     * <p>`SourceFetchException` failures become source outcomes and do not cancel peer workers. A
     * coordinator interruption or unexpected worker/programming failure still aborts the whole operation.</p>
     *
     * @param sources source configurations in desired result order
     * @return source outcomes in the same order as {@code sources}
     */
    List<SourceFetchOutcome> fetchAll(List<ConfiguredSource> sources) {
        List<ConfiguredSource> snapshot = List.copyOf(Objects.requireNonNull(sources, "sources"));
        if (snapshot.isEmpty()) {
            return List.of();
        }

        int workerCount = Math.min(configuration.getMaxConcurrency(), snapshot.size());
        AtomicInteger nextIndex = new AtomicInteger();
        AtomicReferenceArray<SourceFetchOutcome> results = new AtomicReferenceArray<>(snapshot.size());
        ExecutorCompletionService<Void> completions = new ExecutorCompletionService<>(blockingExecutor);
        List<Future<Void>> workers = new ArrayList<>(workerCount);

        for (int worker = 0; worker < workerCount; worker++) {
            workers.add(completions.submit(() -> {
                fetchWorker(snapshot, nextIndex, results);
                return null;
            }));
        }

        try {
            for (int completed = 0; completed < workerCount; completed++) {
                completions.take().get();
            }
        } catch (InterruptedException interrupted) {
            cancel(workers);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Source fetch coordination was interrupted", interrupted);
        } catch (ExecutionException failedWorker) {
            cancel(workers);
            Throwable cause = failedWorker.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Source fetch worker failed", cause);
        }

        return orderedResults(results);
    }

    private void fetchWorker(
            List<ConfiguredSource> sources,
            AtomicInteger nextIndex,
            AtomicReferenceArray<SourceFetchOutcome> results) {
        while (true) {
            int index = nextIndex.getAndIncrement();
            if (index >= sources.size()) {
                return;
            }

            ConfiguredSource source = sources.get(index);
            try {
                FetchedSourceContent content = Objects.requireNonNull(
                        sourceClient.fetch(source),
                        "ExternalSourceClient returned null content");
                results.set(index, new SourceFetchOutcome.Success(source, content));
            } catch (SourceFetchException failure) {
                results.set(index, new SourceFetchOutcome.Failure(source, failure));
            }
        }
    }

    private void cancel(List<Future<Void>> workers) {
        workers.forEach(worker -> worker.cancel(true));
    }

    private List<SourceFetchOutcome> orderedResults(AtomicReferenceArray<SourceFetchOutcome> results) {
        List<SourceFetchOutcome> ordered = new ArrayList<>(results.length());
        for (int index = 0; index < results.length(); index++) {
            ordered.add(Objects.requireNonNull(results.get(index), "Missing source fetch outcome at index " + index));
        }
        return List.copyOf(ordered);
    }
}
