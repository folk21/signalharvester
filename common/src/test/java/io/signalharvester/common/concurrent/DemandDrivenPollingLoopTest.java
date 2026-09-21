package io.signalharvester.common.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Verifies generic demand, delayed scheduling, and cancellation semantics for shared polling lifecycle. */
class DemandDrivenPollingLoopTest {

    /** Consume exactly the requested items without scheduling another poll after demand reaches zero. */
    @Test
    void shouldConsumeRequestedItemsWithoutExtraSchedule() throws Exception {
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Queue<String> items = new ArrayDeque<>(List.of("one", "two"));
            List<String> consumed = new ArrayList<>();
            CountDownLatch delivered = new CountDownLatch(2);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            DemandDrivenPollingLoop<String> loop = new DemandDrivenPollingLoop<>(
                    executor,
                    (delay, task) -> {
                        throw new AssertionError("No delayed poll expected after demand is satisfied");
                    },
                    Duration.ofSeconds(1),
                    () -> Optional.ofNullable(items.poll()),
                    item -> {
                        consumed.add(item);
                        delivered.countDown();
                    },
                    failure::set);

            loop.request(2);

            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("one", "two"), consumed);
            assertNull(failure.get());
        }
    }

    /** Cancel an idle delayed poll before it can run again. */
    @Test
    void shouldCancelScheduledPoll() throws Exception {
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            CountDownLatch scheduled = new CountDownLatch(1);
            AtomicReference<FutureTask<Void>> scheduledTask = new AtomicReference<>();
            DemandDrivenPollingLoop<String> loop = new DemandDrivenPollingLoop<>(
                    executor,
                    (delay, task) -> {
                        FutureTask<Void> future = new FutureTask<>(task, null);
                        scheduledTask.set(future);
                        scheduled.countDown();
                        return future;
                    },
                    Duration.ofSeconds(1),
                    Optional::empty,
                    item -> {
                        throw new AssertionError("No item expected");
                    },
                    failure -> {
                        throw new AssertionError("No failure expected", failure);
                    });

            loop.request(1);
            assertTrue(scheduled.await(2, TimeUnit.SECONDS));

            loop.cancel();

            assertTrue(scheduledTask.get().isCancelled());
            assertTrue(loop.isCancelled());
        }
    }

    /** Request interruption of a blocking poll and suppress its unwind failure after cancellation. */
    @Test
    void shouldInterruptActivePollWhenCancelled() throws Exception {
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            DemandDrivenPollingLoop<String> loop = new DemandDrivenPollingLoop<>(
                    executor,
                    (delay, task) -> {
                        throw new AssertionError("No delayed poll expected while the active poll is blocked");
                    },
                    Duration.ofSeconds(1),
                    () -> {
                        started.countDown();
                        try {
                            Thread.sleep(Duration.ofMinutes(1));
                            throw new AssertionError("Blocking poll should finish through interruption");
                        } catch (InterruptedException exception) {
                            interrupted.countDown();
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("poll interrupted", exception);
                        }
                    },
                    item -> {
                        throw new AssertionError("No item expected");
                    },
                    failure::set);

            loop.request(1);
            assertTrue(started.await(2, TimeUnit.SECONDS));

            loop.cancel();

            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertTrue(loop.isCancelled());
        }
    }
}
