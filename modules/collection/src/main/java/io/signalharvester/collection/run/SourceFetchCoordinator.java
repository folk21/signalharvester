package io.signalharvester.collection.run;

import io.micronaut.scheduling.TaskExecutors;
import io.signalharvester.collection.configuration.CollectionConfiguration;
import io.signalharvester.collection.source.ExternalSourceClient;
import io.signalharvester.collection.source.FetchedSourceContent;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Coordinates bounded external-source fetching on Micronaut's blocking executor.
 *
 * <p>On the Java 21 project baseline Micronaut's blocking executor uses Virtual Threads. The
 * coordinator starts only the configured number of workers, preserves source ordering, and stops
 * assigning new work after the first failure. It does not own or shut down the injected executor.</p>
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
     * Fetches all supplied sources with bounded concurrency and deterministic result ordering.
     *
     * <p>This method waits for the worker set to complete. Callers that originate on a Netty event
     * loop, such as blocking REST controller operations, must themselves be offloaded with
     * {@code @ExecuteOn(TaskExecutors.BLOCKING)} before invoking it.</p>
     *
     * @param sources source configurations in desired result order
     * @return fetched source contents in the same order as {@code sources}
     */
    public List<FetchedSourceContent> fetchAll(List<ConfiguredSource> sources) {
        List<ConfiguredSource> snapshot = List.copyOf(Objects.requireNonNull(sources, "sources"));
        if (snapshot.isEmpty()) {
            return List.of();
        }

        int workerCount = Math.min(configuration.getMaxConcurrency(), snapshot.size());
        AtomicInteger nextIndex = new AtomicInteger();
        AtomicBoolean stopped = new AtomicBoolean();
        AtomicReferenceArray<FetchedSourceContent> results = new AtomicReferenceArray<>(snapshot.size());
        ExecutorCompletionService<Void> completions = new ExecutorCompletionService<>(blockingExecutor);
        List<Future<Void>> workers = new ArrayList<>(workerCount);

        for (int worker = 0; worker < workerCount; worker++) {
            workers.add(completions.submit(() -> {
                fetchWorker(snapshot, nextIndex, stopped, results);
                return null;
            }));
        }

        try {
            for (int completed = 0; completed < workerCount; completed++) {
                completions.take().get();
            }
        } catch (InterruptedException interrupted) {
            stopped.set(true);
            cancel(workers);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Source fetch coordination was interrupted", interrupted);
        } catch (ExecutionException failedWorker) {
            stopped.set(true);
            cancel(workers);
            Throwable cause = failedWorker.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Source fetch worker failed", cause);
        }

        return orderedResults(results);
    }

    /**
     * Processes pending source indexes sequentially on one worker until work is exhausted or stopped.
     */
    private void fetchWorker(
            List<ConfiguredSource> sources,
            AtomicInteger nextIndex,
            AtomicBoolean stopped,
            AtomicReferenceArray<FetchedSourceContent> results) {
        while (!stopped.get()) {
            int index = nextIndex.getAndIncrement();
            if (index >= sources.size()) {
                return;
            }

            try {
                FetchedSourceContent content = Objects.requireNonNull(
                        sourceClient.fetch(sources.get(index)),
                        "ExternalSourceClient returned null content");
                results.set(index, content);
            } catch (RuntimeException failure) {
                stopped.set(true);
                throw failure;
            }
        }
    }

    private void cancel(List<Future<Void>> workers) {
        workers.forEach(worker -> worker.cancel(true));
    }

    private List<FetchedSourceContent> orderedResults(
            AtomicReferenceArray<FetchedSourceContent> results) {
        List<FetchedSourceContent> ordered = new ArrayList<>(results.length());
        for (int index = 0; index < results.length(); index++) {
            ordered.add(results.get(index));
        }
        return List.copyOf(ordered);
    }
}
