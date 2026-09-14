package io.signalharvester.configuration.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies intrinsic monitoring-profile invariants independently from Micronaut and persistence. */
class ConfiguredMonitoringProfileTest {
    private static final SourceId SOURCE_ID = SourceId.of(UUID.fromString("10000000-0000-0000-0000-000000000001"));

    /** Normalize text and preserve effective configuration. */
    @Test
    void shouldNormalizeTextAndPreserveConfiguration() {
        ConfiguredMonitoringProfile profile = new ConfiguredMonitoringProfile(
                MonitoringProfileId.of(UUID.fromString("20000000-0000-0000-0000-000000000001")),
                "  Java jobs  ",
                "  JOB  ",
                true,
                15,
                List.of(SOURCE_ID),
                Map.of("keywords", "java,spring"));

        assertEquals("Java jobs", profile.name());
        assertEquals("JOB", profile.informationCategory());
        assertEquals(List.of(SOURCE_ID), profile.sourceIds());
    }

    /** Reject profiles without a source or a positive collection interval. */
    @Test
    void shouldRejectIncompleteProfile() {
        MonitoringProfileId id = MonitoringProfileId.of(UUID.randomUUID());
        assertThrows(IllegalArgumentException.class,
                () -> new ConfiguredMonitoringProfile(id, "Jobs", "JOB", true, 0, List.of(SOURCE_ID), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ConfiguredMonitoringProfile(id, "Jobs", "JOB", true, 15, List.of(), Map.of()));
    }

    /** Reject duplicate source membership so persistence ordering remains unambiguous. */
    @Test
    void shouldRejectDuplicateSources() {
        assertThrows(IllegalArgumentException.class, () -> new ConfiguredMonitoringProfile(
                MonitoringProfileId.of(UUID.randomUUID()),
                "Jobs",
                "JOB",
                true,
                15,
                List.of(SOURCE_ID, SOURCE_ID),
                Map.of()));
    }
}
