package de.sodaeconomy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Central validation and normalization rules for all monetary values. */
public final class Money {
    public static final long MINOR_UNITS_PER_MAJOR_UNIT = 100L;
    private static final int SCALE = 2;
    private static final BigDecimal MINOR_UNIT_FACTOR = BigDecimal.valueOf(MINOR_UNITS_PER_MAJOR_UNIT);
    private static final BigDecimal MAXIMUM_MAJOR_UNITS = BigDecimal.valueOf(Long.MAX_VALUE)
            .divide(MINOR_UNIT_FACTOR, SCALE, RoundingMode.DOWN);

    private Money() { }

    public static boolean isValid(double amount) {
        return Double.isFinite(amount);
    }

    public static boolean isPositive(double amount) {
        return isValid(amount) && amount > 0.0D;
    }

    public static double normalize(double amount) {
        return fromMinorUnits(toMinorUnits(amount));
    }

    /** Converts a validated, normalized public API amount to exact persisted minor units. */
    public static long toMinorUnits(double amount) {
        if (!isValid(amount)) {
            throw new IllegalArgumentException("Money values must be finite");
        }
        return toMinorUnits(BigDecimal.valueOf(amount));
    }

    /**
     * Parses a user or configuration value into the canonical minor-unit representation.
     * Rounding is always half-up to two decimal places, matching the legacy public API while
     * avoiding binary floating-point rounding at the persistence boundary.
     */
    public static long parseMinorUnits(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Money values must not be blank");
        }
        try {
            return toMinorUnits(new BigDecimal(value.trim()));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Money values must be valid decimal numbers", exception);
        }
    }

    /** Converts a canonical minor-unit amount to a fixed-scale decimal for calculations. */
    public static BigDecimal toBigDecimal(long amountMinor) {
        return BigDecimal.valueOf(amountMinor, SCALE);
    }

    /** Converts a decimal amount to canonical minor units using the documented half-up rule. */
    public static long toMinorUnits(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        BigDecimal normalized = amount.setScale(SCALE, RoundingMode.HALF_UP);
        if (normalized.abs().compareTo(MAXIMUM_MAJOR_UNITS) > 0) {
            throw new IllegalArgumentException("Money value is out of range");
        }
        try {
            return normalized.movePointRight(SCALE).longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Money value is out of range", exception);
        }
    }

    /** Converts exact persisted minor units back to the legacy public double representation. */
    public static double fromMinorUnits(long amountMinor) {
        return amountMinor / (double) MINOR_UNITS_PER_MAJOR_UNIT;
    }
}
