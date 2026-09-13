package io.signalharvester.configuration.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies invariants and defensive-copy behavior of the published configuration model
 * {@link ConfiguredSource}, including source identifiers, locations, enabled state, and settings.
 *
 * <p>Related specification: {@code backend-configuration-persistence-rest}.</p>
 */
class ConfiguredSourceTest {

    /**
     * Create defensive copy of settings.
     */
    @Test
    void shouldCreateDefensiveCopyOfSettings() {
        Map<String, String> settings = new HashMap<>();
        settings.put("query", "java backend");

        ConfiguredSource source = source(URI.create("https://example.test/jobs"), settings);

        settings.put("query", "changed");

        assertEquals("java backend", source.settings().get("query"));
        assertThrows(UnsupportedOperationException.class, () -> source.settings().put("new", "value"));
    }

    /**
     * Accept HTTP location with query and case-insensitive scheme.
     */
    @Test
    void shouldAcceptHttpLocationWithQueryAndCaseInsensitiveScheme() {
        URI location = URI.create("HTTPS://example.test/jobs?topic=java&level=senior");

        ConfiguredSource source = source(location, Map.of());

        assertEquals(location, source.location());
    }

    /**
     * Reject blank name.
     */
    @Test
    void shouldRejectBlankName() {
        assertThrows(IllegalArgumentException.class, () -> new ConfiguredSource(
                SourceId.of(UUID.randomUUID()),
                " ",
                SourceType.RSS,
                URI.create("https://example.test/feed"),
                true,
                Map.of()));
    }

    /**
     * Reject relative location.
     */
    @Test
    void shouldRejectRelativeLocation() {
        assertThrows(IllegalArgumentException.class, () -> source(URI.create("/jobs"), Map.of()));
    }

    /**
     * Reject unsupported location scheme.
     */
    @Test
    void shouldRejectUnsupportedLocationScheme() {
        assertThrows(IllegalArgumentException.class, () -> source(
                URI.create("file:///tmp/source.json"), Map.of()));
    }

    /**
     * Reject location without host.
     */
    @Test
    void shouldRejectLocationWithoutHost() {
        assertThrows(IllegalArgumentException.class, () -> source(
                URI.create("https:/missing-host"), Map.of()));
    }

    /**
     * Reject embedded credentials.
     */
    @Test
    void shouldRejectEmbeddedCredentials() {
        assertThrows(IllegalArgumentException.class, () -> source(
                URI.create("https://user:secret@example.test/jobs"), Map.of()));
    }

    /**
     * Reject fragment because it is not sent to HTTP server.
     */
    @Test
    void shouldRejectFragmentBecauseItIsNotSentToHttpServer() {
        assertThrows(IllegalArgumentException.class, () -> source(
                URI.create("https://example.test/jobs#section"), Map.of()));
    }

    private static ConfiguredSource source(URI location, Map<String, String> settings) {
        return new ConfiguredSource(
                SourceId.of(UUID.randomUUID()),
                "Jobs API",
                SourceType.REST,
                location,
                true,
                settings);
    }
}
