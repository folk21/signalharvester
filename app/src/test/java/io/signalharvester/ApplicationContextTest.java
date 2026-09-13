package io.signalharvester;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies composition-root startup through {@link io.signalharvester.Application} without requiring
 * external infrastructure, protecting the basic module wiring expected from the runnable backend.
 *
 * <p>Related specification: {@code backend-project-structure}.</p>
 */
class ApplicationContextTest {

    /**
     * Start application context without external infrastructure.
     */
    @Test
    void shouldStartApplicationContextWithoutExternalInfrastructure() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
                "datasources.default.enabled", false,
                "flyway.datasources.default.enabled", false,
                "kafka.enabled", false))) {
            assertTrue(context.isRunning());
        }
    }
}
