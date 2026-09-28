package io.signalharvester.testing;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.awaitility.core.ConditionTimeoutException;

/** Provides deterministic bounded polling for asynchronous tests through Awaitility. */
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

        AtomicReference<T> lastValue = new AtomicReference<>();
        try {
            return await()
                    .alias(description)
                    .pollDelay(Duration.ZERO)
                    .pollInterval(interval)
                    .atMost(timeout)
                    .until(() -> {
                        T value = probe.get();
                        lastValue.set(value);
                        return value;
                    }, complete);
        } catch (ConditionTimeoutException timeoutFailure) {
            throw new AssertionError(
                    "Timed out waiting for " + description + "; last=" + lastValue.get(), timeoutFailure);
        }
    }

    /** Supplies a polled value and may propagate checked failures from the underlying test operation. */
    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
