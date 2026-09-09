package io.signalharvester.collection.run;

import io.signalharvester.collection.source.ExternalSourceClient;
import io.signalharvester.collection.source.FetchedSourceContent;
import io.signalharvester.configuration.api.ConfiguredSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Fetches independent external sources concurrently on Java Virtual Threads.
 *
 * <p>The coordinator keeps batch concurrency separate from transport implementation. A semaphore
 * bounds the number of simultaneous external calls while Virtual Threads keep the blocking code
 * straightforward. Results preserve input order so downstream behavior remains deterministic even
 * when network calls complete in a different order.</p>
 */
public final class VirtualThreadSourceFetchCoordinator {

    private final ExternalSourceClient sourceClient;
    private final int maxConcurrency;

    /**
     * Creates a coordinator for one external-source transport boundary.
     *
     * @param sourceClient client used to fetch each configured source
     * @param maxConcurrency maximum number of simultaneous external fetches
     */
    public VirtualThreadSourceFetchCoordinator(ExternalSourceClient sourceClient, int maxConcurrency) {
        this.sourceClient = Objects.requireNonNull(sourceClient, "sourceClient");
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException("maxConcurrency must be positive");
        }
        this.maxConcurrency = maxConcurrency;
    }

    /**
     * Fetches all supplied sources concurrently while returning results in source-list order.
     *
     * <p>If any fetch fails, unfinished tasks are cancelled and the original runtime failure is
     * propagated. This method does not implement retry policy; retries belong to orchestration where
     * source-specific semantics can be applied deliberately.</p>
     *
     * @param sources configured sources to fetch
     * @return fetched contents in the same order as the input
     */
    public List<FetchedSourceContent> fetchAll(List<ConfiguredSource> sources) {
        Objects.requireNonNull(sources, "sources");
        List<ConfiguredSource> snapshot = List.copyOf(sources);
        Semaphore permits = new Semaphore(maxConcurrency);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<FetchedSourceContent>> futures = snapshot.stream()
                    .map(source -> executor.submit(() -> fetchWithPermit(source, permits)))
                    .toList();

            try {
                List<FetchedSourceContent> results = new ArrayList<>(futures.size());
                for (Future<FetchedSourceContent> future : futures) {
                    results.add(await(future));
                }
                return List.copyOf(results);
            } catch (RuntimeException exception) {
                futures.forEach(future -> future.cancel(true));
                throw exception;
            }
        }
    }

    private FetchedSourceContent fetchWithPermit(ConfiguredSource source, Semaphore permits) {
        try {
            permits.acquire();
            try {
                return sourceClient.fetch(source);
            } finally {
                permits.release();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to fetch source", exception);
        }
    }

    private FetchedSourceContent await(Future<FetchedSourceContent> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for source fetch", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Source fetch failed", exception.getCause());
        }
    }
}
