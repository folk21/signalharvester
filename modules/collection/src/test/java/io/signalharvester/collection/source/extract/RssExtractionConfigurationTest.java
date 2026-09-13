package io.signalharvester.collection.source.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micronaut.context.ApplicationContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies validated runtime bounds exposed by {@link RssExtractionConfiguration} for per-response
 * RSS/Atom item cardinality.
 *
 * <p>Related specification: {@code backend-rss-atom-extraction}.</p>
 */
class RssExtractionConfigurationTest {

    /**
     * Expose the default bounded item limit.
     */
    @Test
    void shouldExposeDefaultItemLimit() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("kafka.enabled", false))) {
            assertEquals(500, context.getBean(RssExtractionConfiguration.class).getMaxItemsPerSource());
        }
    }

    /**
     * Reject an item limit above the supported safety bound.
     */
    @Test
    void shouldRejectExcessiveItemLimit() {
        assertThrows(RuntimeException.class, () -> {
            try (ApplicationContext ignored = ApplicationContext.run(Map.of(
                    "signalharvester.collection.rss.max-items-per-source", 10_001,
                    "kafka.enabled", false))) {
            }
        });
    }
}
