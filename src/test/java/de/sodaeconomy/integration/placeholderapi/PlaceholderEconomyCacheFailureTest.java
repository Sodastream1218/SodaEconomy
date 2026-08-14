package de.sodaeconomy.integration.placeholderapi;

import de.sodaeconomy.storage.RuntimeConfigSnapshot;
import de.sodaeconomy.support.InMemoryStorage;
import de.sodaeconomy.support.MockBukkitTestBase;
import de.sodaeconomy.transaction.TransactionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceholderEconomyCacheFailureTest extends MockBukkitTestBase {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000035");

    private TransactionService transactions;
    private PlaceholderEconomyCache cache;

    @AfterEach
    void closeCustomServices() {
        if (cache != null) cache.close();
        if (transactions != null) transactions.close();
    }

    @Test
    void retainsTheLastSuccessfulSnapshotsWhenBackendRefreshFails() throws Exception {
        InMemoryStorage storage = new InMemoryStorage();
        storage.setBalance(PLAYER, 125D);
        storage.setBankBalance(PLAYER, 25D);
        transactions = new TransactionService(plugin, storage, 0D, 0D);
        cache = new PlaceholderEconomyCache(plugin, transactions, true);
        PlaceholderResolver resolver = new PlaceholderResolver(cache, this::settings, true);

        assertTrue(cache.refreshNow().get(5L, TimeUnit.SECONDS));
        assertEquals("125.00", resolver.resolve(PLAYER, "balance"));
        assertEquals("25.00", resolver.resolve(PLAYER, "bank_balance"));
        assertEquals("150.00", resolver.resolve(PLAYER, "total_balance"));

        storage.setBalance(PLAYER, 999D);
        storage.setBankBalance(PLAYER, 999D);
        storage.setFailReads(true);

        assertFalse(cache.refreshNow().get(5L, TimeUnit.SECONDS));
        assertEquals("125.00", resolver.resolve(PLAYER, "balance"),
                "A failed wallet refresh must keep the last successful wallet snapshot.");
        assertEquals("25.00", resolver.resolve(PLAYER, "bank_balance"),
                "A failed bank refresh must keep the last successful bank snapshot.");
        assertEquals("150.00", resolver.resolve(PLAYER, "total_balance"));

        storage.setFailReads(false);
        storage.clearBalancesForTest();
        assertTrue(cache.refreshNow().get(5L, TimeUnit.SECONDS));
        assertEquals("0.00", resolver.resolve(PLAYER, "balance"),
                "A successful empty snapshot is valid economy data and may clear stale cached entries.");
        assertEquals("0.00", resolver.resolve(PLAYER, "bank_balance"));
    }

    @Test
    void reportsUnavailableBeforeAnySuccessfulSnapshotInsteadOfInventingZero() throws Exception {
        InMemoryStorage storage = new InMemoryStorage();
        storage.setBalance(PLAYER, 125D);
        storage.setBankBalance(PLAYER, 25D);
        storage.setFailReads(true);
        transactions = new TransactionService(plugin, storage, 0D, 0D);
        cache = new PlaceholderEconomyCache(plugin, transactions, true);
        PlaceholderResolver resolver = new PlaceholderResolver(cache, this::settings, true);

        assertFalse(cache.refreshNow().get(5L, TimeUnit.SECONDS));

        assertEquals(PlaceholderResolver.UNAVAILABLE_VALUE, resolver.resolve(PLAYER, "balance"));
        assertEquals(PlaceholderResolver.UNAVAILABLE_VALUE, resolver.resolve(PLAYER, "bank_balance"));
        assertEquals(PlaceholderResolver.UNAVAILABLE_VALUE, resolver.resolve(PLAYER, "total_balance"));
        assertEquals(PlaceholderResolver.UNAVAILABLE_VALUE, resolver.resolve(PLAYER, "rank"));
        assertEquals("$", resolver.resolve(PLAYER, "currency_symbol"),
                "Config-only placeholders remain available during storage outages.");
    }

    @Test
    void refreshesWalletIndependentlyWhenBankSnapshotFails() throws Exception {
        SelectiveSnapshotFailureStorage storage = new SelectiveSnapshotFailureStorage();
        storage.setBalance(PLAYER, 100D);
        storage.setBankBalance(PLAYER, 50D);
        transactions = new TransactionService(plugin, storage, 0D, 0D);
        cache = new PlaceholderEconomyCache(plugin, transactions, true);
        PlaceholderResolver resolver = new PlaceholderResolver(cache, this::settings, true);

        assertTrue(cache.refreshNow().get(5L, TimeUnit.SECONDS));
        assertEquals("100.00", resolver.resolve(PLAYER, "balance"));
        assertEquals("50.00", resolver.resolve(PLAYER, "bank_balance"));

        storage.setBalance(PLAYER, 200D);
        storage.setBankBalance(PLAYER, 75D);
        storage.failBankSnapshot = true;

        assertFalse(cache.refreshNow().get(5L, TimeUnit.SECONDS));
        assertEquals("200.00", resolver.resolve(PLAYER, "balance"),
                "A healthy wallet source should still refresh if the bank source is unavailable.");
        assertEquals("50.00", resolver.resolve(PLAYER, "bank_balance"),
                "The bank source should keep its last successful snapshot.");
        assertEquals("250.00", resolver.resolve(PLAYER, "total_balance"));
    }

    private RuntimeConfigSnapshot settings() {
        return new RuntimeConfigSnapshot(
                new RuntimeConfigSnapshot.PrefixSettings(true, "[Soda] "),
                new RuntimeConfigSnapshot.LeaderboardSettings(true, 10, 100),
                new RuntimeConfigSnapshot.CurrencySettings("$", false));
    }

    /** Test-only storage that can fail one snapshot source without affecting the other. */
    private static final class SelectiveSnapshotFailureStorage extends InMemoryStorage {
        private volatile boolean failBankSnapshot;

        @Override
        public synchronized java.util.Map<UUID, Double> getAllBankBalances() {
            if (failBankSnapshot) throw new IllegalStateException("Simulated bank snapshot failure");
            return super.getAllBankBalances();
        }
    }
}
