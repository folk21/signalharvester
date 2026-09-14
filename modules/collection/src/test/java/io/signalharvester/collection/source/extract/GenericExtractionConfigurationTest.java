package io.signalharvester.collection.source.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micronaut.context.ApplicationContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies validated runtime bounds for generic JSON and HTML extraction. */
class GenericExtractionConfigurationTest {

    /** Expose the default bounded candidate-item limit. */
    @Test
    void shouldExposeDefaultItemLimit() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("kafka.enabled", false))) {
            assertEquals(500, context.getBean(GenericExtractionConfiguration.class).getMaxItemsPerSource());
        }
    }

    /** Reject a generic item limit above the supported safety bound. */
    @Test
    void shouldRejectExcessiveItemLimit() {
        assertThrows(RuntimeException.class, () -> {
            try (ApplicationContext ignored = ApplicationContext.run(Map.of(
                    "signalharvester.collection.extraction.max-items-per-source", 10_001,
                    "kafka.enabled", false))) {
            }
        });
    }
}
