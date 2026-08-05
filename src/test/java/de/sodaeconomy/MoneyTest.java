package de.sodaeconomy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyTest {

    @Test
    void normalizesToTwoDecimalPlaces() {
        assertEquals(12.35D, Money.normalize(12.345D));
        assertEquals(-12.34D, Money.normalize(-12.344D));
    }

    @Test
    void usesHalfUpRoundingAtBinaryFloatingPointBoundaries() {
        assertEquals(101L, Money.toMinorUnits(1.005D));
        assertEquals(2L, Money.parseMinorUnits("0.015"));
        assertEquals(1.01D, Money.normalize(1.005D));
    }

    @Test
    void identifiesFiniteValues() {
        assertTrue(Money.isValid(0D));
        assertTrue(Money.isValid(-10D));
        assertFalse(Money.isValid(Double.NaN));
        assertFalse(Money.isValid(Double.POSITIVE_INFINITY));
        assertFalse(Money.isValid(Double.NEGATIVE_INFINITY));
    }

    @Test
    void onlyAcceptsFinitePositiveValuesAsPositive() {
        assertTrue(Money.isPositive(0.01D));
        assertFalse(Money.isPositive(0D));
        assertFalse(Money.isPositive(-0.01D));
        assertFalse(Money.isPositive(Double.NaN));
        assertFalse(Money.isPositive(Double.POSITIVE_INFINITY));
    }

    @Test
    void rejectsNonFiniteValuesDuringNormalization() {
        assertThrows(IllegalArgumentException.class, () -> Money.normalize(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Money.normalize(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> Money.normalize(Double.NEGATIVE_INFINITY));
    }

    @Test
    void rejectsValuesThatOverflowDuringScaling() {
        assertThrows(IllegalArgumentException.class, () -> Money.normalize(Double.MAX_VALUE));
    }
}
