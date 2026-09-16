package io.signalharvester.collection.source.access;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Verifies address classification and explicit CIDR allowances for feature {@code SECURITY.EXTERNAL_SOURCE_ACCESS}. */
class OutboundDestinationPolicyTest {

    /** Reject unsafe IPv4 and IPv6 destination classes in secure mode. */
    @ParameterizedTest
    @ValueSource(strings = {
        "0.0.0.0",
        "127.0.0.1",
        "169.254.169.254",
        "10.0.0.1",
        "100.64.0.1",
        "172.16.1.1",
        "192.168.1.1",
        "224.0.0.1",
        "::",
        "::1",
        "fe80::1",
        "fc00::1",
        "ff02::1"
    })
    void shouldRejectUnsafeAddressesInSecureMode(String literal) throws Exception {
        OutboundDestinationPolicy policy = policy(OutboundAccessConfiguration.Mode.SECURE, "");

        assertThrows(
                OutboundAccessDeniedException.class,
                () -> policy.authorize("source.test", List.of(InetAddress.getByName(literal))));
    }

    /** Permit public IPv4 and IPv6 destinations in secure mode. */
    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "2001:4860:4860::8888"})
    void shouldPermitPublicAddressesInSecureMode(String literal) throws Exception {
        OutboundDestinationPolicy policy = policy(OutboundAccessConfiguration.Mode.SECURE, "");

        assertDoesNotThrow(() -> policy.authorize("source.test", List.of(InetAddress.getByName(literal))));
    }

    /** Allow explicitly configured private and loopback ranges while keeping unrelated private ranges blocked. */
    @Test
    void shouldApplyExplicitCidrAllowRules() throws Exception {
        OutboundDestinationPolicy policy = policy(
                OutboundAccessConfiguration.Mode.SECURE,
                "127.0.0.0/8,fc00::/7");

        assertDoesNotThrow(() -> policy.authorize("loopback.test", List.of(InetAddress.getByName("127.0.0.1"))));
        assertDoesNotThrow(() -> policy.authorize("internal.test", List.of(InetAddress.getByName("fd00::42"))));
        assertThrows(
                OutboundAccessDeniedException.class,
                () -> policy.authorize("other.test", List.of(InetAddress.getByName("10.0.0.1"))));
    }

    /** Reject a DNS answer set when any returned address is blocked. */
    @Test
    void shouldRejectMixedDnsAnswerSet() throws Exception {
        OutboundDestinationPolicy policy = policy(OutboundAccessConfiguration.Mode.SECURE, "");

        assertThrows(
                OutboundAccessDeniedException.class,
                () -> policy.authorize(
                        "mixed.test",
                        List.of(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1"))));
    }

    /** Preserve deterministic loopback/private workflows only in explicit trusted-local mode. */
    @Test
    void shouldPermitLocalDestinationsInTrustedLocalMode() throws Exception {
        OutboundDestinationPolicy policy = policy(OutboundAccessConfiguration.Mode.TRUSTED_LOCAL, "");

        assertDoesNotThrow(() -> policy.authorize(
                "fixture.test",
                List.of(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("10.0.0.5"))));
        assertThrows(
                OutboundAccessDeniedException.class,
                () -> policy.authorize("invalid.test", List.of(InetAddress.getByName("0.0.0.0"))));
    }

    private static OutboundDestinationPolicy policy(OutboundAccessConfiguration.Mode mode, String allowedCidrs) {
        return new OutboundDestinationPolicy(new OutboundAccessConfiguration() {
            @Override
            public Mode getMode() {
                return mode;
            }

            @Override
            public String getAllowedCidrs() {
                return allowedCidrs;
            }
        });
    }
}
