package io.signalharvester.collection.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import java.util.Set;
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

            CompletableFuture<List<SourceFetchOutcome>> result = CompletableFuture.supplyAsync(
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
                    .map(SourceFetchOutcome.Success.class::cast)
                    .map(SourceFetchOutcome.Success::content)
                    .map(content -> new String(content.body(), StandardCharsets.UTF_8))
                    .toList());
            assertTrue(client.onlyVirtualThreadsUsed());
        }
    }

    @Test
    void shouldContinueQueuedWorkAfterSourceFailure() {
        AtomicInteger started = new AtomicInteger();
        ExternalSourceClient client = source -> {
            started.incrementAndGet();
            if ("two".equals(source.name())) {
                throw new SourceFetchException(source.id(), source.location(), "synthetic failure");
            }
            return content(source);
        };

        try (ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor()) {
            SourceFetchCoordinator coordinator = new SourceFetchCoordinator(client, () -> 1, virtualThreads);
            List<SourceFetchOutcome> outcomes = coordinator.fetchAll(List.of(
                    source("one"), source("two"), source("three")));

            assertEquals(3, started.get());
            assertInstanceOf(SourceFetchOutcome.Success.class, outcomes.get(0));
            SourceFetchOutcome.Failure failure = assertInstanceOf(SourceFetchOutcome.Failure.class, outcomes.get(1));
            assertEquals("two", failure.source().name());
            assertInstanceOf(SourceFetchException.class, failure.cause());
            assertInstanceOf(SourceFetchOutcome.Success.class, outcomes.get(2));
        }
    }

    @Test
    void shouldNotCancelInFlightPeerWhenAnotherSourceFails() throws Exception {
        CountDownLatch blockingStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocking = new CountDownLatch(1);
        Set<String> started = ConcurrentHashMap.newKeySet();

        ExternalSourceClient client = source -> {
            started.add(source.name());
            if ("blocking".equals(source.name())) {
                blockingStarted.countDown();
                try {
                    releaseBlocking.await();
                    return content(source);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("blocking source must not be cancelled", interrupted);
                }
            }
            if ("failing".equals(source.name())) {
                try {
                    assertTrue(blockingStarted.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("synthetic failing source interrupted", interrupted);
                }
                throw new SourceFetchException(source.id(), source.location(), "synthetic failure");
            }
            return content(source);
        };

        try (ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor()) {
            SourceFetchCoordinator coordinator = new SourceFetchCoordinator(client, () -> 2, virtualThreads);
            CompletableFuture<List<SourceFetchOutcome>> result = CompletableFuture.supplyAsync(
                    () -> coordinator.fetchAll(List.of(source("blocking"), source("failing"), source("queued"))));

            assertTrue(blockingStarted.await(2, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!started.contains("queued") && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertTrue(started.contains("queued"));
            releaseBlocking.countDown();

            List<SourceFetchOutcome> outcomes = result.get(2, TimeUnit.SECONDS);
            assertInstanceOf(SourceFetchOutcome.Success.class, outcomes.get(0));
            assertInstanceOf(SourceFetchOutcome.Failure.class, outcomes.get(1));
            assertInstanceOf(SourceFetchOutcome.Success.class, outcomes.get(2));
        }
    }

    @Test
    void shouldAbortOnUnexpectedWorkerFailure() {
        AtomicInteger started = new AtomicInteger();
        ExternalSourceClient client = source -> {
            started.incrementAndGet();
            throw new IllegalStateException("programming failure");
        };

        try (ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor()) {
            SourceFetchCoordinator coordinator = new SourceFetchCoordinator(client, () -> 1, virtualThreads);

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> coordinator.fetchAll(List.of(source("one"), source("two"))));

            assertEquals("programming failure", failure.getMessage());
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

    private static FetchedSourceContent content(ConfiguredSource source) {
        return new FetchedSourceContent(
                source.id(),
                source.location(),
                200,
                Optional.of("text/plain"),
                source.name().getBytes(StandardCharsets.UTF_8),
                Instant.parse("2026-09-10T10:00:00Z"));
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
                return content(source);
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
