package io.signalharvester.common.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Verifies shared fail-fast guard semantics used by immutable backend models.
 *
 * <p>Related feature: {@code TESTING.DETERMINISTIC_LOCAL}.</p>
 */
class PreconditionsTest {

    /** Preserve null-versus-blank exception semantics for required strings. */
    @Test
    void shouldValidateRequiredStringsWithStableExceptionSemantics() {
        assertThrows(NullPointerException.class, () -> Preconditions.requireNonBlank(null, "value"));
        assertThrows(IllegalArgumentException.class, () -> Preconditions.requireNonBlank(" ", "value"));
        assertThrows(IllegalArgumentException.class, () -> Preconditions.requireNonBlankArgument(null, "value"));
        assertEquals("value", Preconditions.requireNonBlankArgument("value", "value"));
    }

    /** Validate optional strings, numeric guards, ranges, and trimmed values. */
    @Test
    void shouldValidateCommonImmutableModelPreconditions() {
        assertEquals(Optional.of("value"), Preconditions.requireOptionalNonBlank(Optional.of("value"), "value"));
        assertThrows(IllegalArgumentException.class,
                () -> Preconditions.requireOptionalNonBlank(Optional.of(" "), "value"));
        assertEquals(1, Preconditions.requirePositive(1, "count"));
        assertEquals(0, Preconditions.requireNonNegative(0, "offset"));
        assertEquals(100, Preconditions.requireRange(100, 0, 100, "score"));
        assertEquals("admin", Preconditions.requireTrimmedNonBlank(" admin ", "username"));
    }
}
