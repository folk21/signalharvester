package io.signalharvester.collection.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.core.propagation.PropagatedContext;
import io.micronaut.core.propagation.PropagatedContextElement;
import io.signalharvester.collection.observability.CollectionObservability;
import io.signalharvester.collection.source.ExternalSourceClient;
import io.signalharvester.collection.source.FetchedSourceContent;
import io.signalharvester.collection.source.SourceFetchException;
import io.signalharvester.configuration.api.ConfiguredSource;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.configuration.api.SourceType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link SourceFetchCoordinator} bounded concurrency, payload backpressure, source-index retention,
 * peer failure isolation, and cancellation when run-level coordination or executor dispatch fails.
 *
 * <p>Related specification: {@code backend-collection-run-orchestration}.</p>
 *
 * <p>Features: {@code RUNTIME.CONCURRENCY}, {@code COLLECTION.RUNS}.</p>
 */
class SourceFetchCoordinatorTest {

    /**
     * Bound virtual thread fetches and allow caller to reconstruct input order.
     */
    @Test
    void shouldBoundVirtualThreadFetchesAndAllowCallerToReconstructInputOrder() throws Exception {
        ControlledSourceClient client = new ControlledSourceClient();
        try (ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor()) {
            SourceFetchCoordinator coordinator = new SourceFetchCoordinator(
                    client, () -> 2, observability(), virtualThreads);
            List<ConfiguredSource> sources = List.of(source("one"), source("two"), source("three"));

            CompletableFuture<List<SourceFetchOutcome>> result = CompletableFuture.supplyAsync(
                    () -> collect(coordinator, sources));

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

    /**
     * Apply backpressure until completed payload is handled.
     */
    @Test
    void shouldApplyBackpressureUntilCompletedPayloadIsHandled() throws Exception {
        ControlledSourceClient client = new ControlledSourceClient();
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);

        try (ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor()) {
            SourceFetchCoordinator coordinator = new SourceFetchCoordinator(
                    client, () -> 2, observability(), virtualThreads);
            List<ConfiguredSource> sources = List.of(source("one"), source("two"), source("three"));

            CompletableFuture<Void> result = CompletableFuture.runAsync(() -> coordinator.fetchEach(
                    sources,
                    (sourceIndex, outcome) -> {
                        if (sourceIndex == 1) {
                            handlerStarted.countDown();
                            await(releaseHandler);
                        }
                    }));

            assertTrue(client.awaitStarted(2));
            client.release("two");
            assertTrue(handlerStarted.await(2, TimeUnit.SECONDS));

            Thread.sleep(50);
            assertEquals(2, client.startedCount(), "replacement fetch must wait for terminal handling");

            releaseHandler.countDown();
            assertTrue(client.awaitStarted(3));
            client.release("one");
            client.release("three");
            result.get(2, TimeUnit.SECONDS);
        }
    }

    /**
     * Continue queued work after source failure.
     */
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
            SourceFetchCoordinator coordinator = new SourceFetchCoordinator(
                    client, () -> 1, observability(), virtualThreads);
            List<SourceFetchOutcome> outcomes = collect(coordinator, List.of(
                    source("one"), source("two"), source("three")));

            assertEquals(3, started.get());
            assertInstanceOf(SourceFetchOutcome.Success.class, outcomes.get(0));
            SourceFetchOutcome.Failure failure = assertInstanceOf(SourceFetchOutcome.Failure.class, outcomes.get(1));
            assertEquals("two", failure.source().name());
            assertInstanceOf(SourceFetchException.class, failure.cause());
            assertInstanceOf(SourceFetchOutcome.Success.class, outcomes.get(2));
        }
    }

    /**
     * Not cancel in-flight peer when another source fails.
     */
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
            SourceFetchCoordinator coordinator = new SourceFetchCoordinator(
                    client, () -> 2, observability(), virtualThreads);
            CompletableFuture<List<SourceFetchOutcome>> result = CompletableFuture.supplyAsync(
                    () -> collect(coordinator, List.of(source("blocking"), source("failing"), source("queued"))));

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

    /** Propagate worker-thread interruption to the coordinator and cancel peer work. */
    @Test
    void shouldPropagateInterruptedWorkerAndCancelPeerFetch() throws Exception {
        CountDownLatch blockingStarted = new CountDownLatch(1);
        CountDownLatch blockingInterrupted = new CountDownLatch(1);
        CountDownLatch releaseBlocking = new CountDownLatch(1);
        AtomicBoolean coordinatorInterrupted = new AtomicBoolean();

        ExternalSourceClient client = source -> {
            if ("blocking".equals(source.name())) {
                blockingStarted.countDown();
                try {
                    releaseBlocking.await();
                    return content(source);
                } catch (InterruptedException interrupted) {
                    blockingInterrupted.countDown();
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("synthetic peer cancellation", interrupted);
                }
            }

            await(blockingStarted);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("synthetic worker interruption");
        };

        try (ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor()) {
            SourceFetchCoordinator coordinator = new SourceFetchCoordinator(
                    client, () -> 2, observability(), virtualThreads);
            CompletableFuture<Void> result = CompletableFuture.runAsync(() -> {
                try {
                    coordinator.fetchEach(
                            List.of(source("blocking"), source("interrupted")),
                            (index, outcome) -> {});
                } catch (RuntimeException failure) {
                    coordinatorInterrupted.set(Thread.currentThread().isInterrupted());
                    Thread.interrupted();
                    throw failure;
                }
            });

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> result.get(2, TimeUnit.SECONDS));
            assertEquals("Source fetch coordination was interrupted", failure.getCause().getMessage());
            assertTrue(coordinatorInterrupted.get(), "coordinator thread must preserve lifecycle interruption");
            assertTrue(blockingInterrupted.await(2, TimeUnit.SECONDS),
                    "worker interruption must cancel an in-flight peer fetch");
        } finally {
            releaseBlocking.countDown();
        }
    }

    /**
     * Abort on unexpected worker failure.
     */
    @Test
    void shouldAbortOnUnexpectedWorkerFailure() {
        AtomicInteger started = new AtomicInteger();
        ExternalSourceClient client = source -> {
            started.incrementAndGet();
            throw new IllegalStateException("programming failure");
        };

        try (ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor()) {
            SourceFetchCoordinator coordinator = new SourceFetchCoordinator(
                    client, () -> 1, observability(), virtualThreads);

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> coordinator.fetchEach(List.of(source("one"), source("two")), (index, outcome) -> {}));

            assertEquals("programming failure", failure.getMessage());
            assertEquals(1, started.get());
        }
    }

    /** Cancel an in-flight peer when terminal outcome handling aborts the run. */
    @Test
    void shouldCancelInFlightPeerWhenOutcomeHandlerFails() throws Exception {
        CountDownLatch blockingStarted = new CountDownLatch(1);
        CountDownLatch blockingInterrupted = new CountDownLatch(1);
        CountDownLatch releaseBlocking = new CountDownLatch(1);
        ExternalSourceClient client = source -> {
            if ("blocking".equals(source.name())) {
                blockingStarted.countDown();
                try {
                    releaseBlocking.await();
                } catch (InterruptedException interrupted) {
                    blockingInterrupted.countDown();
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("synthetic peer cancellation", interrupted);
                }
            } else if ("handled".equals(source.name())) {
                await(blockingStarted);
            }
            return content(source);
        };

        try (ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor()) {
            SourceFetchCoordinator coordinator = new SourceFetchCoordinator(
                    client, () -> 2, observability(), virtualThreads);
            CompletableFuture<Void> result = CompletableFuture.runAsync(() -> coordinator.fetchEach(
                    List.of(source("blocking"), source("handled")),
                    (sourceIndex, outcome) -> {
                        if (sourceIndex == 1) {
                            throw new IllegalStateException("synthetic handler failure");
                        }
                    }));

            assertTrue(blockingStarted.await(2, TimeUnit.SECONDS));
            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> result.get(2, TimeUnit.SECONDS));
            assertEquals("synthetic handler failure", failure.getCause().getMessage());
            assertTrue(blockingInterrupted.await(2, TimeUnit.SECONDS),
                    "handler failure must interrupt an in-flight peer fetch");
        } finally {
            releaseBlocking.countDown();
        }
    }

    /** Cancel already accepted initial fetches when a later initial submission is rejected. */
    @Test
    void shouldCancelAcceptedInitialFetchesWhenExecutorRejectsPartialDispatch() {
        AtomicInteger started = new AtomicInteger();
        ExternalSourceClient client = source -> {
            started.incrementAndGet();
            return content(source);
        };

        try (RejectSecondSubmissionExecutor executor = new RejectSecondSubmissionExecutor()) {
            SourceFetchCoordinator coordinator = new SourceFetchCoordinator(
                    client, () -> 2, observability(), executor);

            assertThrows(RejectedExecutionException.class, () -> coordinator.fetchEach(
                    List.of(source("one"), source("two")),
                    (index, outcome) -> {}));

            executor.runAcceptedTask();
            assertEquals(0, started.get(), "accepted peer must be cancelled when initial dispatch aborts");
        }
    }

    /** Preserve Micronaut propagated context across the custom executor fan-out. */
    @Test
    void shouldPropagateContextIntoFetchWorker() {
        ExternalSourceClient client = source -> {
            TestContextElement element = PropagatedContext.getOrEmpty()
                    .find(TestContextElement.class)
                    .orElseThrow(() -> new AssertionError("propagated context must be available in fetch worker"));
            assertEquals("collection-trace", element.value());
            return content(source);
        };

        try (ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor();
                PropagatedContext.Scope ignored = PropagatedContext.getOrEmpty()
                        .plus(new TestContextElement("collection-trace"))
                        .propagate()) {
            SourceFetchCoordinator coordinator = new SourceFetchCoordinator(
                    client, () -> 1, observability(), virtualThreads);

            List<SourceFetchOutcome> outcomes = collect(coordinator, List.of(source("one")));

            assertInstanceOf(SourceFetchOutcome.Success.class, outcomes.getFirst());
        }
    }

    /**
     * Return empty batch without submitting or handling work.
     */
    @Test
    void shouldReturnEmptyBatchWithoutSubmittingOrHandlingWork() {
        AtomicInteger started = new AtomicInteger();
        AtomicInteger handled = new AtomicInteger();
        ExternalSourceClient client = source -> {
            started.incrementAndGet();
            throw new AssertionError("client must not be invoked");
        };

        try (ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor()) {
            SourceFetchCoordinator coordinator = new SourceFetchCoordinator(
                    client, () -> 3, observability(), virtualThreads);

            coordinator.fetchEach(List.of(), (index, outcome) -> handled.incrementAndGet());

            assertEquals(0, started.get());
            assertEquals(0, handled.get());
        }
    }

    private static List<SourceFetchOutcome> collect(
            SourceFetchCoordinator coordinator,
            List<ConfiguredSource> sources) {
        SourceFetchOutcome[] outcomes = new SourceFetchOutcome[sources.size()];
        coordinator.fetchEach(sources, (sourceIndex, outcome) -> outcomes[sourceIndex] = outcome);

        List<SourceFetchOutcome> ordered = new ArrayList<>(outcomes.length);
        for (int index = 0; index < outcomes.length; index++) {
            ordered.add(outcomes[index]);
        }
        return List.copyOf(ordered);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("synthetic handler interrupted", interrupted);
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

    /** Executor that accepts one queued task and rejects the next submission deterministically. */
    private static final class RejectSecondSubmissionExecutor extends AbstractExecutorService {
        private final AtomicReference<Runnable> accepted = new AtomicReference<>();
        private final AtomicBoolean shutdown = new AtomicBoolean();

        @Override
        public void execute(Runnable command) {
            if (shutdown.get()) {
                throw new RejectedExecutionException("executor is shut down");
            }
            if (!accepted.compareAndSet(null, command)) {
                throw new RejectedExecutionException("synthetic partial dispatch rejection");
            }
        }

        void runAcceptedTask() {
            Runnable command = accepted.getAndSet(null);
            if (command != null) {
                command.run();
            }
        }

        @Override
        public void shutdown() {
            shutdown.set(true);
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown.set(true);
            Runnable command = accepted.getAndSet(null);
            return command == null ? List.of() : List.of(command);
        }

        @Override
        public boolean isShutdown() {
            return shutdown.get();
        }

        @Override
        public boolean isTerminated() {
            return shutdown.get() && accepted.get() == null;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }
    }

    private record TestContextElement(String value) implements PropagatedContextElement {
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
    private static CollectionObservability observability() {
        return new CollectionObservability(Optional.empty(), Optional.empty());
    }

}
