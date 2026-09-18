package io.signalharvester.collection.configuration;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micronaut.context.ApplicationContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies fail-fast validation and defaults exposed by {@link CollectionConfiguration} for collection
 * concurrency and external-source resource limits.
 *
 * <p>Related specification: {@code backend-collection-run-orchestration}.</p>
 *
 * <p>Features: {@code RUNTIME.CONCURRENCY}, {@code EVENTING.PIPELINE}.</p>
 */
class CollectionConfigurationTest {

    /**
     * Reject non-positive concurrency at context startup.
     */
    @Test
    void shouldRejectNonPositiveConcurrencyAtContextStartup() {
        assertThrows(RuntimeException.class, () -> {
            try (ApplicationContext ignored = ApplicationContext.run(Map.of(
                    "signalharvester.collection.max-concurrency", 0,
                    "kafka.enabled", false))) {
            }
        });
    }

    /**
     * Reject blank raw item topic at context startup.
     */
    @Test
    void shouldRejectBlankRawItemTopicAtContextStartup() {
        assertThrows(RuntimeException.class, () -> {
            try (ApplicationContext ignored = ApplicationContext.run(Map.of(
                    "signalharvester.kafka.raw-item-discovered-topic", "   ",
                    "kafka.enabled", false))) {
            }
        });
    }
}
