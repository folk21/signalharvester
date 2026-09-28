package io.signalharvester.operations.assisted;

import java.util.Locale;

/** Runtime policy for automatic provider-assisted investigation triggers. */
public enum AutomaticInvestigationMode {
    OFF,
    EVENT,
    EVENT_AND_PERIODIC;

    /** Parses the external configuration value without accepting hidden aliases. */
    public static AutomaticInvestigationMode parse(String value) {
        if (value == null || value.isBlank()) {
            return OFF;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "off" -> OFF;
            case "event" -> EVENT;
            case "event-and-periodic" -> EVENT_AND_PERIODIC;
            default -> throw new IllegalArgumentException(
                    "automaticMode must be one of off, event, event-and-periodic");
        };
    }
}
