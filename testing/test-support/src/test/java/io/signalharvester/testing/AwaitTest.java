package io.signalharvester.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Verifies bounded polling success and timeout diagnostics for asynchronous test support. */
class AwaitTest {

    /** Return the first probed value that satisfies the completion predicate. */
    @Test
    void shouldReturnCompletedProbeValue() throws Exception {
        AtomicInteger attempts = new AtomicInteger();

        int value = Await.until(
                "counter to reach three",
                Duration.ofSeconds(1),
                Duration.ofMillis(1),
                attempts::incrementAndGet,
                current -> current >= 3);

        assertEquals(3, value);
    }

    /** Include the semantic description and last value when polling times out. */
    @Test
    void shouldReportLastValueOnTimeout() {
        AssertionError error = assertThrows(AssertionError.class, () -> Await.until(
                "never-complete probe",
                Duration.ofMillis(10),
                Duration.ofMillis(1),
                () -> "pending",
                "ready"::equals));

        assertTrue(error.getMessage().contains("never-complete probe"));
        assertTrue(error.getMessage().contains("last=pending"));
    }
}
