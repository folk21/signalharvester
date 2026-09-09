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

        ConfiguredSource source = new ConfiguredSource(
                SourceId.of(UUID.randomUUID()),
                "Jobs API",
                SourceType.REST,
                URI.create("https://example.test/jobs"),
                true,
                settings);

        settings.put("query", "changed");

        assertEquals("java backend", source.settings().get("query"));
        assertThrows(UnsupportedOperationException.class, () -> source.settings().put("new", "value"));
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
        assertThrows(IllegalArgumentException.class, () -> new ConfiguredSource(
                SourceId.of(UUID.randomUUID()),
                "Relative source",
                SourceType.HTML,
                URI.create("/jobs"),
                true,
                Map.of()));
    }
}
