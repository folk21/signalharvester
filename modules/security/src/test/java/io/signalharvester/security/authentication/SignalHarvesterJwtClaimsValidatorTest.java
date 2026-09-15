package io.signalharvester.security.authentication;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.security.token.MapClaims;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies stable principal and role-claim validation for feature {@code SECURITY.AUTHENTICATION}. */
class SignalHarvesterJwtClaimsValidatorTest {
    private static final String USER_ID = "00000000-0000-0000-0000-000000000123";

    private final SignalHarvesterJwtClaimsValidator validator = new SignalHarvesterJwtClaimsValidator();

    /** Accept a stable UUID subject and only known explicit role names. */
    @Test
    void shouldAcceptSupportedPrincipalClaims() {
        assertTrue(validator.validate(new MapClaims(Map.of(
                "sub", USER_ID,
                "roles", List.of("USER", "VIEWER"))), null));
    }

    /** Reject missing, malformed, or unknown authorization claims. */
    @Test
    void shouldRejectMalformedPrincipalClaims() {
        assertFalse(validator.validate(new MapClaims(Map.of("roles", List.of("USER"))), null));
        assertFalse(validator.validate(new MapClaims(Map.of("sub", "not-a-uuid", "roles", List.of("USER"))), null));
        assertFalse(validator.validate(new MapClaims(Map.of("sub", USER_ID, "roles", "USER")), null));
        assertFalse(validator.validate(new MapClaims(Map.of("sub", USER_ID, "roles", List.of("SUPERADMIN"))), null));
        assertFalse(validator.validate(new MapClaims(Map.of("sub", UUID.randomUUID().toString(), "roles", List.of())), null));
    }
}
