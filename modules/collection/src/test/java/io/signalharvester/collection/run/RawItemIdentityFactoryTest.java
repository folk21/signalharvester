package io.signalharvester.collection.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.signalharvester.collection.source.FetchedSourceContent;
import io.signalharvester.configuration.api.SourceId;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies deterministic identifiers produced by {@link RawItemIdentityFactory}, including which payload and
 * provenance changes must preserve or change raw-item identity.
 *
 * <p>Related specification: {@code backend-collection-run-orchestration}.</p>
 */
class RawItemIdentityFactoryTest {

    private static final SourceId SOURCE_ID = SourceId.of(
            UUID.fromString("00000000-0000-0000-0000-000000000501"));
    private static final SourceId OTHER_SOURCE_ID = SourceId.of(
            UUID.fromString("00000000-0000-0000-0000-000000000502"));
    private static final URI JOBS_URI = URI.create("https://example.test/jobs");
    private static final URI PAGED_JOBS_URI = URI.create("https://example.test/jobs?page=2");

    private final RawItemIdentityFactory factory = new RawItemIdentityFactory();

    /**
     * Keep identity stable across fetch timestamps.
     */
    @Test
    void shouldKeepIdentityStableAcrossFetchTimestamps() {
        FetchedSourceContent first = content(SOURCE_ID, JOBS_URI, "payload",
                Instant.parse("2026-09-10T10:00:00Z"));
        FetchedSourceContent repeated = content(SOURCE_ID, JOBS_URI, "payload",
                Instant.parse("2026-09-10T11:00:00Z"));

        assertEquals(factory.identityFor(first), factory.identityFor(repeated));
    }

    /**
     * Change identity when payload or provenance changes.
     */
    @Test
    void shouldChangeIdentityWhenPayloadOrProvenanceChanges() {
        FetchedSourceContent original = content(
                SOURCE_ID, JOBS_URI, "payload", Instant.EPOCH);

        assertNotEquals(
                factory.identityFor(original),
                factory.identityFor(content(
                        SOURCE_ID, JOBS_URI, "different", Instant.EPOCH)));
        assertNotEquals(
                factory.identityFor(original),
                factory.identityFor(content(
                        SOURCE_ID, PAGED_JOBS_URI, "payload", Instant.EPOCH)));
        assertNotEquals(
                factory.identityFor(original),
                factory.identityFor(content(
                        OTHER_SOURCE_ID,
                        JOBS_URI,
                        "payload",
                        Instant.EPOCH)));
    }

    private static FetchedSourceContent content(
            SourceId sourceId, URI uri, String body, Instant fetchedAt) {
        return new FetchedSourceContent(
                sourceId,
                uri,
                200,
                Optional.of("text/plain"),
                body.getBytes(StandardCharsets.UTF_8),
                fetchedAt);
    }
}
