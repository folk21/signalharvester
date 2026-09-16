package io.signalharvester.collection.source.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micronaut.context.ApplicationContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies outbound-access configuration binding and fail-fast CIDR validation. */
class OutboundAccessConfigurationTest {

    /** Default collection mode remains explicitly trusted-local for existing deterministic workflows. */
    @Test
    void shouldDefaultToTrustedLocalMode() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("kafka.enabled", false))) {
            OutboundAccessConfiguration configuration = context.getBean(OutboundAccessConfiguration.class);

            assertEquals(OutboundAccessConfiguration.Mode.TRUSTED_LOCAL, configuration.getMode());
        }
    }

    /** Bind secure mode and explicit CIDR allow rules. */
    @Test
    void shouldBindSecureModeAndAllowedCidrs() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
                "signalharvester.collection.outbound-access.mode", "SECURE",
                "signalharvester.collection.outbound-access.allowed-cidrs", "10.0.0.0/8,fd00::/8",
                "kafka.enabled", false))) {
            OutboundAccessConfiguration configuration = context.getBean(OutboundAccessConfiguration.class);

            assertEquals(OutboundAccessConfiguration.Mode.SECURE, configuration.getMode());
            assertEquals("10.0.0.0/8,fd00::/8", configuration.getAllowedCidrs());
        }
    }

    /** Reject malformed operator CIDR rules when the application context starts. */
    @Test
    void shouldRejectMalformedAllowedCidrAtStartup() {
        assertThrows(RuntimeException.class, () -> {
            try (ApplicationContext ignored = ApplicationContext.run(Map.of(
                    "signalharvester.collection.outbound-access.allowed-cidrs", "internal.example/24",
                    "kafka.enabled", false))) {
            }
        });
    }
}
