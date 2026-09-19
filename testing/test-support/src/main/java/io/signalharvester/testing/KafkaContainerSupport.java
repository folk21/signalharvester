package io.signalharvester.testing;

import java.time.Duration;
import org.testcontainers.kafka.KafkaContainer;

/** Builds the repository-standard Kafka Testcontainer with a bounded readiness budget. */
public final class KafkaContainerSupport {
    private static final String KAFKA_IMAGE = "apache/kafka-native:3.8.0";
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(2);

    private KafkaContainerSupport() {
    }

    /** Creates a Kafka container configured for integration-test startup on constrained developer Docker runtimes. */
    public static KafkaContainer create() {
        return new KafkaContainer(KAFKA_IMAGE)
                .withStartupTimeout(STARTUP_TIMEOUT);
    }
}
