package de.sodaeconomy.integration.placeholderapi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PlaceholderValueFormatterTest {

    @Test
    void rawFormattingPreservesExactMinorUnitsBeyondDoubleIntegerPrecision() {
        long minor = 9_007_199_254_740_993L;

        assertEquals("90071992547409.93", PlaceholderValueFormatter.rawMinor(minor));
        assertEquals(new BigDecimal("90071992547409.93"),
                PlaceholderValueFormatter.totalMajor(minor, 0L));
    }

    @Test
    void shortFormattingUsesStableCompactBoundaries() {
        assertEquals("0", PlaceholderValueFormatter.shortMajor(new BigDecimal("0")));
        assertEquals("999", PlaceholderValueFormatter.shortMajor(new BigDecimal("999")));
        assertEquals("1K", PlaceholderValueFormatter.shortMajor(new BigDecimal("1000")));
        assertEquals("1.25K", PlaceholderValueFormatter.shortMajor(new BigDecimal("1250")));
        assertEquals("1M", PlaceholderValueFormatter.shortMajor(new BigDecimal("999999")));
        assertEquals("1M", PlaceholderValueFormatter.shortMajor(new BigDecimal("1000000")));
        assertEquals("1B", PlaceholderValueFormatter.shortMajor(new BigDecimal("1000000000")));
        assertEquals("-1.25K", PlaceholderValueFormatter.shortMajor(new BigDecimal("-1250")));
    }

    @Test
    void totalUsesIntegerMinorUnitArithmeticWithoutLongOverflow() {
        BigDecimal total = PlaceholderValueFormatter.totalMajor(Long.MAX_VALUE, Long.MAX_VALUE);

        assertEquals(new BigDecimal("184467440737095516.14"), total);
        assertEquals("184467440737095516.14", PlaceholderValueFormatter.rawMajor(total));
    }
}
