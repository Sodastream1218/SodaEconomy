package de.sodaeconomy.storage;

import java.util.Objects;

/**
 * Immutable snapshot of the deliberately small configuration surface that is safe to replace
 * while the plugin is running. The selected language and the independent update-checker settings
 * snapshot are validated and published alongside this snapshot by the plugin reload coordinator,
 * while storage, banking, persistence, debug and other startup-only configuration remain outside
 * these reloadable snapshots and therefore require a restart.
 */
public record RuntimeConfigSnapshot(
        PrefixSettings prefix,
        LeaderboardSettings leaderboard,
        CurrencySettings currency
) {
    public RuntimeConfigSnapshot {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(leaderboard, "leaderboard");
        Objects.requireNonNull(currency, "currency");
    }

    public record PrefixSettings(boolean enabled, String fullPrefix) {
        public PrefixSettings {
            Objects.requireNonNull(fullPrefix, "fullPrefix");
        }
    }

    public record LeaderboardSettings(boolean enabled, int topEntries, int maxEntries) {
        public LeaderboardSettings {
            if (topEntries < 1) {
                throw new IllegalArgumentException("leaderboard.top-entries must be at least 1");
            }
            if (maxEntries < 1) {
                throw new IllegalArgumentException("leaderboard.max-entries must be at least 1");
            }
            if (topEntries > maxEntries) {
                throw new IllegalArgumentException("leaderboard.top-entries must not exceed leaderboard.max-entries");
            }
        }
    }

    public record CurrencySettings(String symbol, boolean displayAfterAmount) {
        public CurrencySettings {
            Objects.requireNonNull(symbol, "symbol");
        }
    }
}
