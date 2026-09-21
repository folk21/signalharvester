package io.signalharvester.common.concurrent;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs one demand-driven blocking polling loop with delayed retry and best-effort cancellation.
 *
 * <p>The caller owns item discovery and delivery semantics. This utility owns only generic task lifecycle,
 * demand accounting, delayed rescheduling, and interruption of the currently submitted blocking task.</p>
 *
 * @param <T> item type emitted by the polling step
 */
public final class DemandDrivenPollingLoop<T> {

    /** Schedules one delayed task and returns a handle that can be cancelled by the loop owner. */
    @FunctionalInterface
    public interface DelayedScheduler {
        Future<?> schedule(Duration delay, Runnable task);
    }

    private final ExecutorService blockingExecutor;
    private final DelayedScheduler scheduler;
    private final Duration pollInterval;
    private final Supplier<Optional<T>> nextItem;
    private final Consumer<T> itemConsumer;
    private final Consumer<Throwable> failureConsumer;
    private final AtomicLong demand = new AtomicLong();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<Future<?>> scheduled = new AtomicReference<>();
    private final AtomicReference<Future<?>> activePoll = new AtomicReference<>();

    public DemandDrivenPollingLoop(
            ExecutorService blockingExecutor,
            DelayedScheduler scheduler,
            Duration pollInterval,
            Supplier<Optional<T>> nextItem,
            Consumer<T> itemConsumer,
            Consumer<Throwable> failureConsumer) {
        this.blockingExecutor = Objects.requireNonNull(blockingExecutor, "blockingExecutor");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.pollInterval = requirePositive(pollInterval, "pollInterval");
        this.nextItem = Objects.requireNonNull(nextItem, "nextItem");
        this.itemConsumer = Objects.requireNonNull(itemConsumer, "itemConsumer");
        this.failureConsumer = Objects.requireNonNull(failureConsumer, "failureConsumer");
    }

    /** Adds downstream demand and starts polling when no poll task is currently active. */
    public void request(long n) {
        if (n <= 0) {
            fail(new IllegalArgumentException("Reactive Streams demand must be positive"));
            return;
        }
        demand.getAndUpdate(current -> addCap(current, n));
        trigger();
    }

    /** Cancels future polling and requests interruption of the currently submitted blocking poll. */
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        cancelScheduled();
        Future<?> poll = activePoll.getAndSet(null);
        if (poll != null) {
            poll.cancel(true);
        }
    }

    /** Returns whether this polling loop has reached its terminal cancellation/failure state. */
    public boolean isCancelled() {
        return cancelled.get();
    }

    private void trigger() {
        if (cancelled.get() || demand.get() == 0 || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            Future<?> poll = blockingExecutor.submit(this::pollAndConsume);
            activePoll.set(poll);
            if (cancelled.get() && activePoll.compareAndSet(poll, null)) {
                poll.cancel(true);
            }
        } catch (RuntimeException failure) {
            running.set(false);
            fail(failure);
        }
    }

    private void pollAndConsume() {
        try {
            while (!cancelled.get() && demand.get() > 0) {
                Optional<T> item = Objects.requireNonNull(nextItem.get(), "nextItem result");
                if (item.isEmpty()) {
                    break;
                }
                if (cancelled.get()) {
                    break;
                }
                itemConsumer.accept(item.orElseThrow());
                demand.getAndUpdate(current -> current == Long.MAX_VALUE ? Long.MAX_VALUE : current - 1);
            }

            if (!cancelled.get() && demand.get() > 0) {
                scheduleNextPoll();
            } else {
                running.set(false);
            }
        } catch (Throwable failure) {
            running.set(false);
            fail(failure);
        }
    }

    private void scheduleNextPoll() {
        Future<?> future = scheduler.schedule(pollInterval, () -> {
            scheduled.set(null);
            running.set(false);
            trigger();
        });
        Future<?> previous = scheduled.getAndSet(future);
        if (previous != null && previous != future) {
            previous.cancel(false);
        }
        if (cancelled.get()) {
            Future<?> cancelledFuture = scheduled.getAndSet(null);
            if (cancelledFuture != null) {
                cancelledFuture.cancel(false);
            }
            running.set(false);
        }
    }

    private void fail(Throwable failure) {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        cancelScheduled();
        failureConsumer.accept(failure);
    }

    private void cancelScheduled() {
        Future<?> future = scheduled.getAndSet(null);
        if (future != null) {
            future.cancel(false);
        }
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private static long addCap(long current, long increment) {
        long updated = current + increment;
        return updated < 0 ? Long.MAX_VALUE : updated;
    }
}
