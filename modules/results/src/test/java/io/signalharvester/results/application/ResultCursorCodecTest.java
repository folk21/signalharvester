package io.signalharvester.results.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Verifies criteria-bound opaque cursor round-trips for feature {@code RESULTS.BROWSING}. */
class ResultCursorCodecTest {

    private static final String PROFILE_ID = "11111111-1111-1111-1111-111111111111";
    private static final String ITEM_ID = "a".repeat(64);
    private static final Instant ANALYZED_AT = Instant.parse("2026-09-18T06:00:00.123456Z");

    /** Round-trip the deterministic sort key and allow a different page size with unchanged filters. */
    @Test
    void shouldRoundTripCursorAndIgnoreLimitInCriteriaFingerprint() {
        ResultCursorCodec codec = new ResultCursorCodec();
        ResultQueryCriteria firstPage = criteria(20, Optional.empty(), Optional.of("java kafka"));
        String cursor = codec.encode(summary(), firstPage);

        ResultPagePosition decoded = codec.decode(cursor, criteria(50, Optional.of(cursor), Optional.of("java kafka")));

        assertEquals(ANALYZED_AT, decoded.analyzedAt());
        assertEquals(PROFILE_ID, decoded.monitoringProfileId());
        assertEquals(ITEM_ID, decoded.normalizedItemId());
    }

    /** Reject malformed cursor data and reuse under materially different search criteria. */
    @Test
    void shouldRejectMalformedAndCriteriaMismatchedCursor() {
        ResultCursorCodec codec = new ResultCursorCodec();
        ResultQueryCriteria original = criteria(20, Optional.empty(), Optional.of("java kafka"));
        String cursor = codec.encode(summary(), original);

        assertThrows(InvalidResultQueryException.class,
                () -> codec.decode("not-base64%", criteria(20, Optional.of("not-base64%"), Optional.of("java kafka"))));
        assertThrows(InvalidResultQueryException.class,
                () -> codec.decode(cursor, criteria(20, Optional.of(cursor), Optional.of("postgresql"))));

        byte[] unsupportedPayload = Base64.getUrlDecoder().decode(cursor);
        unsupportedPayload[0] = 2;
        String unsupported = Base64.getUrlEncoder().withoutPadding().encodeToString(unsupportedPayload);
        assertThrows(InvalidResultQueryException.class,
                () -> codec.decode(unsupported, criteria(20, Optional.of(unsupported), Optional.of("java kafka"))));
    }

    private static ResultQueryCriteria criteria(int limit, Optional<String> cursor, Optional<String> search) {
        return new ResultQueryCriteria(
                limit,
                Optional.of(PROFILE_ID),
                Optional.empty(),
                Optional.of("JOB"),
                Optional.of(true),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                search,
                cursor);
    }

    private static ResultSummary summary() {
        return new ResultSummary(
                PROFILE_ID,
                ITEM_ID,
                "source-a",
                "JOB",
                Optional.empty(),
                Optional.of("Senior Java Engineer"),
                "https://example.test/jobs/1",
                true,
                "MATCHED",
                90,
                Map.of(),
                List.of("java"),
                "Matched",
                "keyword-v1",
                Optional.empty(),
                ANALYZED_AT);
    }
}
