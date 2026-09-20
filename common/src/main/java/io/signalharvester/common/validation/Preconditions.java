package io.signalharvester.common.validation;

import java.util.Objects;
import java.util.Optional;

/** Small reusable guards for fail-fast immutable model construction. */
public final class Preconditions {

    private Preconditions() {
    }

    /** Requires a non-null, non-blank string and preserves {@link NullPointerException} for null input. */
    public static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /** Requires a non-null, non-blank argument and reports both null and blank as invalid arguments. */
    public static String requireNonBlankArgument(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /** Requires a non-null optional whose present value is not blank. */
    public static Optional<String> requireOptionalNonBlank(Optional<String> value, String name) {
        Objects.requireNonNull(value, name);
        value.ifPresent(item -> requireNonBlankArgument(item, name));
        return value;
    }

    /** Requires a strictly positive integer. */
    public static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    /** Requires a strictly positive long value. */
    public static long requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    /** Requires a non-negative integer. */
    public static int requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    /** Requires a non-negative long value. */
    public static long requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    /** Requires an integer inside the inclusive range. */
    public static int requireRange(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    /** Trims a non-null string and requires the normalized value to be non-blank. */
    public static String requireTrimmedNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
