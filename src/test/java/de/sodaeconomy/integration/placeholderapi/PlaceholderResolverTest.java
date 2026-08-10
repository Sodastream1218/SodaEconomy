package de.sodaeconomy.integration.placeholderapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import de.sodaeconomy.storage.RuntimeConfigSnapshot;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PlaceholderResolverTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void resolvesSupportedWalletBankTotalCurrencyAndRankingPlaceholders() {
        FakeBalances balances = new FakeBalances();
        balances.wallets.put(PLAYER, 1_250_050L);
        balances.banks.put(PLAYER, 5_000_000L);
        balances.positions.put(PLAYER, 3);
        AtomicReference<RuntimeConfigSnapshot> settings = new AtomicReference<>(settings("$", false, true, 100));
        PlaceholderResolver resolver = new PlaceholderResolver(balances, settings::get, true);

        assertEquals("12500.50", resolver.resolve(PLAYER, "balance"));
        assertEquals("$12500.50", resolver.resolve(PLAYER, "balance_formatted"));
        assertEquals("12.5K", resolver.resolve(PLAYER, "balance_short"));
        assertEquals("50000.00", resolver.resolve(PLAYER, "bank_balance"));
        assertEquals("$50000.00", resolver.resolve(PLAYER, "bank_balance_formatted"));
        assertEquals("62500.50", resolver.resolve(PLAYER, "total_balance"));
        assertEquals("$62500.50", resolver.resolve(PLAYER, "total_balance_formatted"));
        assertEquals("$", resolver.resolve(PLAYER, "currency_symbol"));
        assertEquals("3", resolver.resolve(PLAYER, "rank"));
        assertEquals("3", resolver.resolve(PLAYER, "baltop_position"));
        assertNull(resolver.resolve(PLAYER, "transactions_total"));
        assertNull(resolver.resolve(PLAYER, "currency"));
        assertNull(resolver.resolve(PLAYER, "unknown"));
    }

    @Test
    void missingOrNullPlayerUsesSafeValuesAndNeverReturnsNullText() {
        PlaceholderResolver resolver = new PlaceholderResolver(new FakeBalances(),
                () -> settings(" Coins", true, true, 100), true);

        assertEquals("0.00", resolver.resolve(null, "balance"));
        assertEquals("0.00 Coins", resolver.resolve(null, "balance_formatted"));
        assertEquals("0", resolver.resolve(null, "balance_short"));
        assertEquals("0.00", resolver.resolve(null, "bank_balance"));
        assertEquals("0.00", resolver.resolve(null, "total_balance"));
        assertEquals("-", resolver.resolve(null, "rank"));
        assertEquals(" Coins", resolver.resolve(null, "currency_symbol"));
        assertNull(resolver.resolve(null, null));
        assertNull(resolver.resolve(null, " "));
    }

    @Test
    void bankDisabledReturnsZeroAndTotalRemainsWalletOnly() {
        FakeBalances balances = new FakeBalances();
        balances.wallets.put(PLAYER, 12_345L);
        balances.banks.put(PLAYER, 99_999L);
        PlaceholderResolver resolver = new PlaceholderResolver(balances,
                () -> settings("$", false, true, 100), false);

        assertEquals("0.00", resolver.resolve(PLAYER, "bank_balance"));
        assertEquals("123.45", resolver.resolve(PLAYER, "total_balance"));
    }

    @Test
    void liveRuntimeSettingsAreReadOnEveryResolutionWithoutExpansionReload() {
        FakeBalances balances = new FakeBalances();
        balances.wallets.put(PLAYER, 1_250L);
        balances.positions.put(PLAYER, 4);
        AtomicReference<RuntimeConfigSnapshot> settings = new AtomicReference<>(settings("$", false, true, 10));
        PlaceholderResolver resolver = new PlaceholderResolver(balances, settings::get, false);

        assertEquals("$12.50", resolver.resolve(PLAYER, "balance_formatted"));
        assertEquals("$", resolver.resolve(PLAYER, "currency_symbol"));
        assertEquals("4", resolver.resolve(PLAYER, "baltop_position"));
        assertEquals("12.50", resolver.resolve(PLAYER, "balance"));

        settings.set(settings(" Credits", true, true, 3));

        assertEquals("12.50 Credits", resolver.resolve(PLAYER, "balance_formatted"));
        assertEquals(" Credits", resolver.resolve(PLAYER, "currency_symbol"));
        assertEquals("-", resolver.resolve(PLAYER, "baltop_position"),
                "The current leaderboard max-entry rule must be observed after reload.");
        assertEquals("12.50", resolver.resolve(PLAYER, "balance"),
                "Raw monetary values must not depend on presentation configuration.");

        settings.set(settings(" Credits", true, false, 3));
        assertEquals("-", resolver.resolve(PLAYER, "rank"));
    }

    @Test
    void identifierParsingIsCaseInsensitiveButStable() {
        FakeBalances balances = new FakeBalances();
        balances.wallets.put(PLAYER, 10_000L);
        PlaceholderResolver resolver = new PlaceholderResolver(balances,
                () -> settings("$", false, true, 100), false);

        assertEquals("100.00", resolver.resolve(PLAYER, "BALANCE"));
    }

    private static RuntimeConfigSnapshot settings(String symbol, boolean after, boolean leaderboardEnabled,
                                                  int leaderboardMax) {
        return new RuntimeConfigSnapshot(
                new RuntimeConfigSnapshot.PrefixSettings(true, "[Soda] "),
                new RuntimeConfigSnapshot.LeaderboardSettings(leaderboardEnabled, 3, leaderboardMax),
                new RuntimeConfigSnapshot.CurrencySettings(symbol, after));
    }

    private static final class FakeBalances implements PlaceholderBalanceView {
        private final Map<UUID, Long> wallets = new HashMap<>();
        private final Map<UUID, Long> banks = new HashMap<>();
        private final Map<UUID, Integer> positions = new HashMap<>();

        @Override
        public long walletBalanceMinor(UUID playerId) {
            return wallets.getOrDefault(playerId, 0L);
        }

        @Override
        public long bankBalanceMinor(UUID playerId) {
            return banks.getOrDefault(playerId, 0L);
        }

        @Override
        public int leaderboardPosition(UUID playerId) {
            return positions.getOrDefault(playerId, 0);
        }
    }
}
