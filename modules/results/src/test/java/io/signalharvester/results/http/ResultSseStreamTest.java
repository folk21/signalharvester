package io.signalharvester.results.http;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.http.sse.Event;
import io.micronaut.scheduling.TaskScheduler;
import io.signalharvester.results.application.ResultLiveBatch;
import io.signalharvester.results.application.ResultLiveCriteria;
import io.signalharvester.results.application.ResultLiveQuery;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/** Verifies that {@link ResultSseStream} cancels active blocking polling when the client disconnects. */
class ResultSseStreamTest {

    /** Interrupt an in-flight blocking Results poll when the SSE subscription is cancelled. */
    @Test
    void shouldInterruptActivePollWhenCancelled() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        InterruptibleResultLiveQuery query = new InterruptibleResultLiveQuery();
        RecordingSubscriber subscriber = new RecordingSubscriber();
        try {
            ResultSseStream stream = new ResultSseStream(query, executor, unusedScheduler(), configuration());
            stream.stream(emptyCriteria(), OptionalLong.of(7L)).subscribe(subscriber);

            subscriber.request(1);
            assertTrue(query.awaitStarted(), "Expected the blocking Results poll to start");

            subscriber.cancel();

            assertTrue(query.awaitInterrupted(), "Expected SSE cancellation to interrupt the active Results poll");
            assertNull(subscriber.failure(), "Client cancellation must not be reported as an SSE failure");
        } finally {
            executor.shutdownNow();
        }
    }

    private static ResultLiveCriteria emptyCriteria() {
        return new ResultLiveCriteria(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ResultSseConfiguration configuration() {
        return new ResultSseConfiguration() {
            @Override
            public Duration getPollInterval() {
                return Duration.ofSeconds(1);
            }

            @Override
            public Duration getKeepaliveInterval() {
                return Duration.ofSeconds(15);
            }

            @Override
            public Duration getReconnectDelay() {
                return Duration.ofSeconds(2);
            }

            @Override
            public int getBatchSize() {
                return 10;
            }
        };
    }

    private static TaskScheduler unusedScheduler() {
        return (TaskScheduler) Proxy.newProxyInstance(
                TaskScheduler.class.getClassLoader(),
                new Class<?>[] {TaskScheduler.class},
                (proxy, method, args) -> {
                    throw new AssertionError("Scheduled polling is not expected in this cancellation test");
                });
    }

    private static final class InterruptibleResultLiveQuery implements ResultLiveQuery {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch interrupted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public long currentCursor() {
            throw new AssertionError("A supplied Last-Event-ID should bypass current-cursor initialization");
        }

        @Override
        public ResultLiveBatch pollAfter(long cursor, ResultLiveCriteria criteria, int maxItems) {
            started.countDown();
            try {
                release.await();
                throw new AssertionError("Blocking poll should only finish through interruption");
            } catch (InterruptedException failure) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Results live poll interrupted", failure);
            }
        }

        boolean awaitStarted() throws InterruptedException {
            return started.await(2, TimeUnit.SECONDS);
        }

        boolean awaitInterrupted() throws InterruptedException {
            return interrupted.await(2, TimeUnit.SECONDS);
        }
    }

    private static final class RecordingSubscriber implements Subscriber<Event<ResultLiveEventResponse>> {
        private final AtomicReference<Subscription> subscription = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        @Override
        public void onSubscribe(Subscription value) {
            subscription.set(value);
        }

        @Override
        public void onNext(Event<ResultLiveEventResponse> event) {
            throw new AssertionError("No Results SSE event expected before cancellation");
        }

        @Override
        public void onError(Throwable throwable) {
            failure.set(throwable);
        }

        @Override
        public void onComplete() {
            throw new AssertionError("Results SSE should not complete before cancellation");
        }

        void request(long n) {
            subscription.get().request(n);
        }

        void cancel() {
            subscription.get().cancel();
        }

        Throwable failure() {
            return failure.get();
        }
    }
}
