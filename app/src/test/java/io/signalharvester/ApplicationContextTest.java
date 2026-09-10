package io.signalharvester;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApplicationContextTest {

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
