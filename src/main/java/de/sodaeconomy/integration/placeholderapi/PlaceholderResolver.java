package de.sodaeconomy.integration.placeholderapi;

import de.sodaeconomy.CurrencyFormatter;
import de.sodaeconomy.Money;
import de.sodaeconomy.storage.RuntimeConfigSnapshot;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Pure placeholder parsing and formatting over an already-cached economy snapshot. */
final class PlaceholderResolver {
    static final String UNAVAILABLE_VALUE = "-";

    private final PlaceholderBalanceView balances;
    private final Supplier<RuntimeConfigSnapshot> runtimeSettings;
    private final boolean bankingEnabled;

    PlaceholderResolver(PlaceholderBalanceView balances, Supplier<RuntimeConfigSnapshot> runtimeSettings,
                        boolean bankingEnabled) {
        this.balances = Objects.requireNonNull(balances, "balances");
        this.runtimeSettings = Objects.requireNonNull(runtimeSettings, "runtimeSettings");
        this.bankingEnabled = bankingEnabled;
    }

    String resolve(UUID playerId, String identifier) {
        if (identifier == null || identifier.isBlank()) return null;
        String normalized = identifier.toLowerCase(Locale.ROOT);
        RuntimeConfigSnapshot settings = runtimeSettings.get();
        RuntimeConfigSnapshot.CurrencySettings currency = settings.currency();

        if (normalized.equals("currency_symbol")) {
            return currency.symbol();
        }

        boolean walletAvailable = playerId == null || balances.walletSnapshotAvailable();
        boolean bankAvailable = playerId == null || !bankingEnabled || balances.bankSnapshotAvailable();
        long walletMinor = playerId == null ? 0L : balances.walletBalanceMinor(playerId);
        long bankMinor = playerId == null || !bankingEnabled ? 0L : balances.bankBalanceMinor(playerId);

        return switch (normalized) {
            case "balance" -> walletAvailable ? PlaceholderValueFormatter.rawMinor(walletMinor) : UNAVAILABLE_VALUE;
            case "balance_formatted" -> walletAvailable
                    ? CurrencyFormatter.formatMinorUnits(walletMinor, currency) : UNAVAILABLE_VALUE;
            case "balance_short" -> walletAvailable ? PlaceholderValueFormatter.shortMinor(walletMinor) : UNAVAILABLE_VALUE;
            case "bank_balance" -> bankAvailable ? PlaceholderValueFormatter.rawMinor(bankMinor) : UNAVAILABLE_VALUE;
            case "bank_balance_formatted" -> bankAvailable
                    ? CurrencyFormatter.formatMinorUnits(bankMinor, currency) : UNAVAILABLE_VALUE;
            case "total_balance" -> walletAvailable && bankAvailable
                    ? PlaceholderValueFormatter.rawMajor(PlaceholderValueFormatter.totalMajor(walletMinor, bankMinor))
                    : UNAVAILABLE_VALUE;
            case "total_balance_formatted" -> walletAvailable && bankAvailable
                    ? CurrencyFormatter.formatMajorUnits(PlaceholderValueFormatter.totalMajor(walletMinor, bankMinor), currency)
                    : UNAVAILABLE_VALUE;
            case "rank", "baltop_position" -> walletAvailable
                    ? resolveRank(playerId, settings.leaderboard()) : UNAVAILABLE_VALUE;
            default -> null;
        };
    }

    private String resolveRank(UUID playerId, RuntimeConfigSnapshot.LeaderboardSettings leaderboard) {
        if (playerId == null || !leaderboard.enabled()) return "-";
        int position = balances.leaderboardPosition(playerId);
        return position > 0 && position <= leaderboard.maxEntries() ? Integer.toString(position) : "-";
    }
}
