package io.signalharvester.testing;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Predicate;

/** Provides deterministic bounded polling for asynchronous tests. */
public final class Await {

    private Await() {
    }

    /** Polls until the supplied probe satisfies the completion predicate or the timeout expires. */
    public static <T> T until(
            String description,
            Duration timeout,
            Duration interval,
            CheckedSupplier<T> probe,
            Predicate<? super T> complete) throws Exception {
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(complete, "complete");
        if (description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("interval must be positive");
        }

        Instant deadline = Instant.now().plus(timeout);
        T lastValue = probe.get();
        while (!complete.test(lastValue)) {
            if (!Instant.now().isBefore(deadline)) {
                throw new AssertionError("Timed out waiting for " + description + "; last=" + lastValue);
            }
            Thread.sleep(interval);
            lastValue = probe.get();
        }
        return lastValue;
    }

    /** Supplies a polled value and may propagate checked failures from the underlying test operation. */
    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
