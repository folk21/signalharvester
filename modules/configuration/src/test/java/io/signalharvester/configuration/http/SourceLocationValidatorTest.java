package io.signalharvester.configuration.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Verifies {@link SourceLocationValidator} accepts the HTTP/HTTPS locations supported by Configuration and
 * rejects missing, malformed, or unsupported external-source locations.
 *
 * <p>Related specification: {@code backend-configuration-persistence-rest}.</p>
 */
class SourceLocationValidatorTest {

    private final SourceLocationValidator validator = new SourceLocationValidator();

    /**
     * Accept domain compatible HTTP locations.
     */
    @Test
    void shouldAcceptDomainCompatibleHttpLocations() {
        assertTrue(validator.isValid("https://example.test/jobs?topic=java", null, null));
        assertTrue(validator.isValid("HTTP://example.test/feed", null, null));
    }

    /**
     * Reject locations rejected by configured source.
     */
    @Test
    void shouldRejectLocationsRejectedByConfiguredSource() {
        assertFalse(validator.isValid("/relative", null, null));
        assertFalse(validator.isValid("file:///tmp/source", null, null));
        assertFalse(validator.isValid("https:/missing-host", null, null));
        assertFalse(validator.isValid("https://user:secret@example.test/jobs", null, null));
        assertFalse(validator.isValid("https://example.test/jobs#fragment", null, null));
        assertFalse(validator.isValid("not a uri", null, null));
    }
}
