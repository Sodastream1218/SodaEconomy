package de.sodaeconomy;

import de.sodaeconomy.storage.RuntimeConfigSnapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Shared source of truth for SodaEconomy's configured monetary display format. */
public final class CurrencyFormatter {
    private CurrencyFormatter() { }

    /** Formats exact minor units using the currently active currency settings. */
    public static String formatMinorUnits(long amountMinor, RuntimeConfigSnapshot.CurrencySettings currency) {
        return formatMajorUnits(Money.toBigDecimal(amountMinor), currency);
    }

    /** Formats an exact major-unit value using the currently active currency settings. */
    public static String formatMajorUnits(BigDecimal amount, RuntimeConfigSnapshot.CurrencySettings currency) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        String numeric = amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
        return currency.displayAfterAmount()
                ? numeric + currency.symbol()
                : currency.symbol() + numeric;
    }
}
