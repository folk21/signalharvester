package io.signalharvester.operations.assisted.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TelemetrySanitizerTest {
    private final TelemetrySanitizer sanitizer = new TelemetrySanitizer();

    @Test
    void shouldRedactCredentialShapesAndBoundOutput() {
        String raw = "Authorization: Bearer top-secret-token password=secret123 "
                + "https://user:pass@example.test/path " + "x".repeat(500);

        String sanitized = sanitizer.sanitize(raw, 180);

        assertFalse(sanitized.contains("top-secret-token"));
        assertFalse(sanitized.contains("secret123"));
        assertFalse(sanitized.contains("user:pass@"));
        assertTrue(sanitized.contains("[REDACTED]"));
        assertTrue(sanitized.contains("[tool result truncated by configured bound]"));
    }
}
