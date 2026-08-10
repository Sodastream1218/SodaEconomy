package de.sodaeconomy.integration.placeholderapi;

import de.sodaeconomy.Money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/** Locale-independent machine and compact number formatting for SodaEconomy placeholders. */
final class PlaceholderValueFormatter {
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1_000L);
    private static final String[] SUFFIXES = {"", "K", "M", "B", "T"};

    private PlaceholderValueFormatter() { }

    static String rawMinor(long amountMinor) {
        return Money.toBigDecimal(amountMinor).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    static String rawMajor(BigDecimal amountMajor) {
        return amountMajor.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    static String shortMinor(long amountMinor) {
        return shortMajor(Money.toBigDecimal(amountMinor));
    }

    static String shortMajor(BigDecimal amountMajor) {
        if (amountMajor.signum() == 0) return "0";
        BigDecimal absolute = amountMajor.abs();
        int suffixIndex = 0;
        while (absolute.compareTo(THOUSAND) >= 0 && suffixIndex < SUFFIXES.length - 1) {
            absolute = absolute.divide(THOUSAND, 12, RoundingMode.HALF_UP);
            suffixIndex++;
        }

        BigDecimal rounded = absolute.setScale(2, RoundingMode.HALF_UP);
        if (rounded.compareTo(THOUSAND) >= 0 && suffixIndex < SUFFIXES.length - 1) {
            rounded = rounded.divide(THOUSAND, 2, RoundingMode.HALF_UP);
            suffixIndex++;
        }
        BigDecimal signed = amountMajor.signum() < 0 ? rounded.negate() : rounded;
        return signed.stripTrailingZeros().toPlainString() + SUFFIXES[suffixIndex];
    }

    static BigDecimal totalMajor(long walletMinor, long bankMinor) {
        BigInteger totalMinor = BigInteger.valueOf(walletMinor).add(BigInteger.valueOf(bankMinor));
        return new BigDecimal(totalMinor, 2);
    }
}
