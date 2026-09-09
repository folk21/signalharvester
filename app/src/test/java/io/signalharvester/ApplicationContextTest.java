package io.signalharvester;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

class ApplicationContextTest {

    @Test
    void shouldStartApplicationContext() {
        try (ApplicationContext context = ApplicationContext.run()) {
            assertTrue(context.isRunning());
        }
    }
}
