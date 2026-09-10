package io.signalharvester.collection.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.signalharvester.collection.source.ExternalSourceClient;
import io.signalharvester.collection.source.FetchedSourceContent;
import io.signalharvester.collection.source.SourceFetchException;
import io.signalharvester.configuration.api.ConfiguredSource;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.configuration.api.SourceType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SourceFetchCoordinatorTest {

    @Test
    void shouldBoundVirtualThreadWorkersAndPreserveInputOrder() throws Exception {
        ControlledSourceClient client = new ControlledSourceClient();
        try (ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor()) {
            SourceFetchCoordinator coordinator = new SourceFetchCoordinator(client, () -> 2, virtualThreads);
            List<ConfiguredSource> sources = List.of(source("one"), source("two"), source("three"));

            CompletableFuture<List<FetchedSourceContent>> result = CompletableFuture.supplyAsync(
                    () -> coordinator.fetchAll(sources));

            assertTrue(client.awaitStarted(2));
            assertEquals(2, client.startedCount());
            assertEquals(2, client.maxActive());

            client.release("two");
            assertTrue(client.awaitStarted(3));
            assertEquals(2, client.maxActive());

            client.release("three");
            client.release("one");

            assertEquals(List.of("one", "two", "three"), result.get(2, TimeUnit.SECONDS).stream()
                    .map(content -> new String(content.body(), StandardCharsets.UTF_8))
                    .toList());
            assertTrue(client.onlyVirtualThreadsUsed());
        }
    }

    @Test
    void shouldStopAssigningNewWorkAfterFirstFailure() {
        AtomicInteger started = new AtomicInteger();
        ExternalSourceClient client = source -> {
            started.incrementAndGet();
            throw new SourceFetchException(source.id(), source.location(), "synthetic failure");
        };

        try (ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor()) {
            SourceFetchCoordinator coordinator = new SourceFetchCoordinator(client, () -> 1, virtualThreads);

            assertThrows(SourceFetchException.class, () -> coordinator.fetchAll(List.of(
                    source("one"), source("two"), source("three"))));

            assertEquals(1, started.get());
        }
    }

    @Test
    void shouldReturnEmptyBatchWithoutSubmittingWork() {
        AtomicInteger started = new AtomicInteger();
        ExternalSourceClient client = source -> {
            started.incrementAndGet();
            throw new AssertionError("client must not be invoked");
        };

        try (ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor()) {
            SourceFetchCoordinator coordinator = new SourceFetchCoordinator(client, () -> 3, virtualThreads);

            assertTrue(coordinator.fetchAll(List.of()).isEmpty());
            assertEquals(0, started.get());
        }
    }

    private static ConfiguredSource source(String name) {
        return new ConfiguredSource(
                new SourceId(java.util.UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8))),
                name,
                SourceType.REST,
                URI.create("https://example.test/" + name),
                true,
                Map.of());
    }

    /** Test double that blocks individual source calls so concurrency can be observed deterministically. */
    private static final class ControlledSourceClient implements ExternalSourceClient {
        private final Map<String, CountDownLatch> releases = new ConcurrentHashMap<>();
        private final AtomicInteger started = new AtomicInteger();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();
        private final AtomicBoolean onlyVirtualThreads = new AtomicBoolean(true);

        @Override
        public FetchedSourceContent fetch(ConfiguredSource source) {
            onlyVirtualThreads.compareAndSet(true, Thread.currentThread().isVirtual());
            started.incrementAndGet();
            int currentActive = active.incrementAndGet();
            maxActive.accumulateAndGet(currentActive, Math::max);
            CountDownLatch release = releases.computeIfAbsent(source.name(), ignored -> new CountDownLatch(1));

            try {
                release.await();
                return new FetchedSourceContent(
                        source.id(),
                        source.location(),
                        200,
                        Optional.of("text/plain"),
                        source.name().getBytes(StandardCharsets.UTF_8),
                        Instant.parse("2026-09-09T10:00:00Z"));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("synthetic source fetch interrupted", interrupted);
            } finally {
                active.decrementAndGet();
            }
        }

        boolean awaitStarted(int expectedCount) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (started.get() < expectedCount && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            return started.get() >= expectedCount;
        }

        void release(String sourceName) {
            releases.computeIfAbsent(sourceName, ignored -> new CountDownLatch(1)).countDown();
        }

        int startedCount() {
            return started.get();
        }

        int maxActive() {
            return maxActive.get();
        }

        boolean onlyVirtualThreadsUsed() {
            return onlyVirtualThreads.get();
        }
    }
}
