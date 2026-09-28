package io.signalharvester.operations.api;

import java.util.Objects;

/** Request-scoped safe metadata supplied by a mutation adapter to the change journal. */
public record OperationalChangeContext(
        boolean journalEnabled,
        OperationalChangeSource source,
        String actorId,
        String correlationId) {

    public OperationalChangeContext {
        Objects.requireNonNull(source, "source");
        actorId = normalize(actorId, "system");
        correlationId = normalize(correlationId, "");
    }

    /** Creates a context for an authenticated or trusted-local REST/UI mutation. */
    public static OperationalChangeContext rest(String actorId, String correlationId) {
        return new OperationalChangeContext(true, OperationalChangeSource.REST_UI, actorId, correlationId);
    }

    /** Creates a context for repository-owned tooling. */
    public static OperationalChangeContext tooling(String actorId, String correlationId) {
        return new OperationalChangeContext(true, OperationalChangeSource.TOOLING, actorId, correlationId);
    }

    /** Creates a context for internal calls that are outside the supported journal guarantee. */
    public static OperationalChangeContext untrackedSystem() {
        return new OperationalChangeContext(false, OperationalChangeSource.SYSTEM, "system", "");
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
