package io.signalharvester.results.application;

import io.signalharvester.results.testing.ResultSummaryFixture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
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
        ResultQueryCriteria firstPage = criteria(20, null, "java kafka");
        String cursor = codec.encode(summary(), firstPage);

        ResultPagePosition decoded = codec.decode(cursor, criteria(50, cursor, "java kafka"));

        assertEquals(ANALYZED_AT, decoded.analyzedAt());
        assertEquals(PROFILE_ID, decoded.monitoringProfileId());
        assertEquals(ITEM_ID, decoded.normalizedItemId());
    }

    /** Reject malformed cursor data and reuse under materially different search criteria. */
    @Test
    void shouldRejectMalformedAndCriteriaMismatchedCursor() {
        ResultCursorCodec codec = new ResultCursorCodec();
        ResultQueryCriteria original = criteria(20, null, "java kafka");
        String cursor = codec.encode(summary(), original);

        assertThrows(InvalidResultQueryException.class,
                () -> codec.decode("not-base64%", criteria(20, "not-base64%", "java kafka")));
        assertThrows(InvalidResultQueryException.class,
                () -> codec.decode(cursor, criteria(20, cursor, "postgresql")));

        byte[] unsupportedPayload = Base64.getUrlDecoder().decode(cursor);
        unsupportedPayload[0] = 2;
        String unsupported = Base64.getUrlEncoder().withoutPadding().encodeToString(unsupportedPayload);
        assertThrows(InvalidResultQueryException.class,
                () -> codec.decode(unsupported, criteria(20, unsupported, "java kafka")));
    }

    /** Reject truncated, overlong, and structurally invalid opaque cursor payloads. */
    @Test
    void shouldRejectStructurallyInvalidCursorPayloads() {
        ResultCursorCodec codec = new ResultCursorCodec();
        ResultQueryCriteria original = criteria(20, null, "java kafka");
        String cursor = codec.encode(summary(), original);
        byte[] payload = Base64.getUrlDecoder().decode(cursor);

        String truncated = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Arrays.copyOf(payload, payload.length - 1));
        assertThrows(InvalidResultQueryException.class,
                () -> codec.decode(truncated, criteria(20, truncated, "java kafka")));

        byte[] overlongPayload = Arrays.copyOf(payload, payload.length + 1);
        overlongPayload[overlongPayload.length - 1] = 1;
        String overlong = Base64.getUrlEncoder().withoutPadding().encodeToString(overlongPayload);
        assertThrows(InvalidResultQueryException.class,
                () -> codec.decode(overlong, criteria(20, overlong, "java kafka")));

        byte[] invalidTimestampPayload = payload.clone();
        ByteBuffer.wrap(invalidTimestampPayload).putLong(1, Long.MAX_VALUE);
        String invalidTimestamp = Base64.getUrlEncoder().withoutPadding().encodeToString(invalidTimestampPayload);
        assertThrows(InvalidResultQueryException.class, () -> codec.decode(
                invalidTimestamp,
                criteria(20, invalidTimestamp, "java kafka")));

        byte[] invalidProfileLengthPayload = payload.clone();
        ByteBuffer.wrap(invalidProfileLengthPayload).putInt(13, 0);
        String invalidProfileLength = Base64.getUrlEncoder().withoutPadding().encodeToString(invalidProfileLengthPayload);
        assertThrows(InvalidResultQueryException.class, () -> codec.decode(
                invalidProfileLength,
                criteria(20, invalidProfileLength, "java kafka")));
    }

    /** Reject sort-key values that cannot be represented safely by the published cursor format. */
    @Test
    void shouldRejectInvalidSortKeysDuringCursorEncoding() {
        ResultCursorCodec codec = new ResultCursorCodec();
        ResultQueryCriteria criteria = criteria(20, null, null);

        ResultSummary invalidProfile = summary("x".repeat(129), ITEM_ID);
        assertThrows(InvalidResultQueryException.class, () -> codec.encode(invalidProfile, criteria));

        ResultSummary invalidItem = summary(PROFILE_ID, "not-a-normalized-item-id");
        assertThrows(InvalidResultQueryException.class, () -> codec.encode(invalidItem, criteria));
    }

    private static ResultQueryCriteria criteria(int limit, String cursor, String search) {
        return new ResultQueryCriteria(
                limit,
                Optional.of(PROFILE_ID),
                Optional.empty(),
                Optional.of("JOB"),
                Optional.of(true),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.ofNullable(search),
                Optional.ofNullable(cursor));
    }

    private static ResultSummary summary() {
        return summary(PROFILE_ID, ITEM_ID);
    }

    private static ResultSummary summary(String profileId, String itemId) {
        return ResultSummaryFixture.resultSummary(profileId, itemId)
                .analyzedAt(ANALYZED_AT)
                .build();
    }
}
