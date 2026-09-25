package io.signalharvester.operations.application;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.micronaut.context.ApplicationContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies Micronaut can instantiate the Stage 2 health-analysis beans in an isolated module context. */
class OperationalHealthBeanWiringTest {

    /** Resolve the Health Engine and signal collector without requiring Operations persistence or scheduled sampling. */
    @Test
    void shouldResolveHealthEngineBeans() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
                "signalharvester.operations.health.sampling-enabled", false))) {
            assertNotNull(context.getBean(HealthEngine.class));
            assertNotNull(context.getBean(OperationalHealthSignalCollector.class));
        }
    }
}
