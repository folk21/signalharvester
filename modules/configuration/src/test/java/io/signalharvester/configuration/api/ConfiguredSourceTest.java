package io.signalharvester.configuration.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConfiguredSourceTest {

    @Test
    void shouldCreateDefensiveCopyOfSettings() {
        Map<String, String> settings = new HashMap<>();
        settings.put("query", "java backend");

        ConfiguredSource source = source(URI.create("https://example.test/jobs"), settings);

        settings.put("query", "changed");

        assertEquals("java backend", source.settings().get("query"));
        assertThrows(UnsupportedOperationException.class, () -> source.settings().put("new", "value"));
    }

    @Test
    void shouldAcceptHttpLocationWithQueryAndCaseInsensitiveScheme() {
        URI location = URI.create("HTTPS://example.test/jobs?topic=java&level=senior");

        ConfiguredSource source = source(location, Map.of());

        assertEquals(location, source.location());
    }

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

    @Test
    void shouldRejectRelativeLocation() {
        assertThrows(IllegalArgumentException.class, () -> source(URI.create("/jobs"), Map.of()));
    }

    @Test
    void shouldRejectUnsupportedLocationScheme() {
        assertThrows(IllegalArgumentException.class, () -> source(
                URI.create("file:///tmp/source.json"), Map.of()));
    }

    @Test
    void shouldRejectLocationWithoutHost() {
        assertThrows(IllegalArgumentException.class, () -> source(
                URI.create("https:/missing-host"), Map.of()));
    }

    @Test
    void shouldRejectEmbeddedCredentials() {
        assertThrows(IllegalArgumentException.class, () -> source(
                URI.create("https://user:secret@example.test/jobs"), Map.of()));
    }

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
