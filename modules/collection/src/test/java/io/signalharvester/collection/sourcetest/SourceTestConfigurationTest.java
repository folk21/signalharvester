package io.signalharvester.collection.sourcetest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micronaut.context.ApplicationContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies defaults and fail-fast bounds for diagnostic source-test previews. */
class SourceTestConfigurationTest {

    /** Expose bounded source-test preview defaults. */
    @Test
    void shouldExposePreviewDefaults() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("kafka.enabled", false))) {
            SourceTestConfiguration configuration = context.getBean(SourceTestConfiguration.class);
            assertEquals(5, configuration.getMaxPreviewItems());
            assertEquals(500, configuration.getMaxPreviewContentChars());
        }
    }

    /** Reject excessive preview cardinality at context startup. */
    @Test
    void shouldRejectExcessivePreviewItemLimit() {
        assertThrows(RuntimeException.class, () -> {
            try (ApplicationContext ignored = ApplicationContext.run(Map.of(
                    "signalharvester.collection.source-test.max-preview-items", 51,
                    "kafka.enabled", false))) {
            }
        });
    }
}
