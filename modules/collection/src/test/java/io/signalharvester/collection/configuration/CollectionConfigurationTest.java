package io.signalharvester.collection.configuration;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micronaut.context.ApplicationContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CollectionConfigurationTest {

    @Test
    void shouldRejectNonPositiveConcurrencyAtContextStartup() {
        assertThrows(RuntimeException.class, () -> {
            try (ApplicationContext ignored = ApplicationContext.run(Map.of(
                    "signalharvester.collection.max-concurrency", 0,
                    "kafka.enabled", false))) {
            }
        });
    }

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
