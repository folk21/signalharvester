package io.signalharvester.testing;

import java.time.Duration;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;

/** Builds the repository-standard Kafka Testcontainer with transport-based readiness. */
public final class KafkaContainerSupport {
    private static final String KAFKA_IMAGE = "apache/kafka-native:3.8.0";
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(2);

    private KafkaContainerSupport() {
    }

    /** Creates Kafka whose mapped broker listener must be reachable before tests perform protocol-level setup. */
    public static KafkaContainer create() {
        return new KafkaContainer(KAFKA_IMAGE)
                .waitingFor(Wait.forListeningPort().withStartupTimeout(STARTUP_TIMEOUT));
    }
}
