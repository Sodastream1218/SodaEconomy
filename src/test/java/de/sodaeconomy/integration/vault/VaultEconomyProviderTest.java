package de.sodaeconomy.integration.vault;

import be.seeseemelk.mockbukkit.entity.PlayerMock;
import de.sodaeconomy.identity.PlayerIdentityApi;
import de.sodaeconomy.support.MockBukkitTestBase;
import de.sodaeconomy.transaction.DurableOperation;
import de.sodaeconomy.transaction.TransactionOrigin;
import de.sodaeconomy.transaction.TransactionQuery;
import de.sodaeconomy.transaction.TransactionRecord;
import de.sodaeconomy.transaction.TransactionResult;
import de.sodaeconomy.transaction.WalletAccountLookup;
import de.sodaeconomy.transaction.WalletAccountState;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class VaultEconomyProviderTest extends MockBukkitTestBase {

    @Test
    void commitsVaultDepositsBeforeReturningSuccessAndKeepsThemAuditableAndRollbackable() throws Exception {
        PlayerMock player = server.addPlayer("VaultUser");
        VaultEconomyProvider provider = provider(new TransactionServiceVaultGateway(plugin.getTransactionService()));

        EconomyResponse response = provider.depositPlayer(player, 25.00D);

        assertTrue(response.transactionSuccess());
        assertEquals(25.00D, response.amount);
        assertEquals(1_025.00D, response.balance);
        assertEquals(1_025.00D, plugin.getStorageManager().getCurrent().getBalance(player.getUniqueId()),
                "Vault must not return SUCCESS before the underlying local storage has committed.");
        TransactionRecord vaultRecord = plugin.getEconomyTransactionApi().findTransactions(TransactionQuery.recent())
                .get().records().stream()
                .filter(record -> "vault".equals(record.metadata().get("integration")))
                .findFirst().orElseThrow();
        assertEquals("deposit", vaultRecord.metadata().get("operation"));
        assertEquals("Vault", vaultRecord.origin().identifier());
        assertEquals(25_00L, vaultRecord.appliedAmountMinor());

        TransactionResult rollback = plugin.getEconomyTransactionApi()
                .rollback(vaultRecord.id(), TransactionOrigin.api("VaultTest"), "Test rollback").get();
        assertTrue(rollback.isSuccessful());
        assertEquals(1_000.00D, provider.getBalance(player));
        assertTrue(plugin.getEconomyTransactionApi().getPlayerStatistics(player.getUniqueId()).get()
                .transactionCount() >= 3L);
    }

    @Test
    void handlesWithdrawalsValidationLimitsAndUnsupportedBanks() {
        PlayerMock player = server.addPlayer("VaultDebit");
        VaultEconomyProvider provider = provider(new TransactionServiceVaultGateway(plugin.getTransactionService()));

        EconomyResponse success = provider.withdrawPlayer(player, 100D);
        assertTrue(success.transactionSuccess());
        assertEquals(900D, success.balance);

        assertEquals(EconomyResponse.ResponseType.FAILURE, provider.withdrawPlayer(player, 10_000D).type);
        assertEquals(EconomyResponse.ResponseType.FAILURE, provider.depositPlayer(player, 100_000D).type);
        assertEquals(EconomyResponse.ResponseType.FAILURE, provider.depositPlayer(player, Double.NaN).type);
        assertEquals(EconomyResponse.ResponseType.FAILURE, provider.depositPlayer(player, Double.POSITIVE_INFINITY).type);
        assertEquals(EconomyResponse.ResponseType.FAILURE, provider.depositPlayer(player, -1D).type);
        assertEquals(EconomyResponse.ResponseType.NOT_IMPLEMENTED, provider.bankDeposit("guild", 10D).type);
        assertFalse(provider.hasBankSupport());
        assertTrue(provider.getBanks().isEmpty());
    }

    @Test
    void resolvesLegacyNamesOnlyThroughTheKnownPlayerIdentityService() throws Exception {
        server.addPlayer("CrossPlayVault");
        VaultEconomyProvider provider = provider(new TransactionServiceVaultGateway(plugin.getTransactionService()));
        int accountsBefore = plugin.getStorageManager().getCurrent().getAllBalances().size();

        assertTrue(provider.depositPlayer("crossplayvault", 5D).transactionSuccess());
        assertEquals(EconomyResponse.ResponseType.FAILURE,
                provider.depositPlayer("DefinitelyUnknownVaultPlayer", 5D).type);
        assertEquals(accountsBefore + 1, plugin.getStorageManager().getCurrent().getAllBalances().size());
    }

    @Test
    void usesReloadedCurrencyFormattingWithoutRecreatingTheProvider() {
        VaultEconomyProvider provider = provider(new TransactionServiceVaultGateway(plugin.getTransactionService()));
        var previous = plugin.getConfigManager().getRuntimeSettings();
        plugin.getConfigManager().applyRuntimeSettings(new de.sodaeconomy.storage.RuntimeConfigSnapshot(
                previous.prefix(), previous.leaderboard(),
                new de.sodaeconomy.storage.RuntimeConfigSnapshot.CurrencySettings(" Coins", true)));

        assertEquals("12.50 Coins", provider.format(12.5D));
    }

    @Test
    void safelyFailsAQueuedMutationWhenTheConfiguredTimeoutExpires() {
        PlayerMock player = server.addPlayer("TimeoutUser");
        QueuedGateway gateway = new QueuedGateway();
        VaultEconomyProvider provider = provider(gateway, new VaultIntegrationSettings(true, 100L, 0L));

        EconomyResponse response = provider.depositPlayer(player, 10D);

        assertEquals(EconomyResponse.ResponseType.FAILURE, response.type);
        assertTrue(gateway.pending.completion().isCompletedExceptionally());
        assertEquals(0, gateway.committedMutations);
    }

    @Test
    void registersAndDeregistersTheProviderThroughBukkitServicesManager() {
        assertNull(server.getServicesManager().getRegistration(Economy.class));
        VaultIntegrationManager integration = new VaultIntegrationManager(plugin, plugin.getTransactionService(),
                plugin.getPlayerIdentityApi(), plugin.getEconomyManager(), plugin.getLanguageManager(),
                new VaultIntegrationSettings(true, 3_000L, 100L));

        RegisteredServiceProvider<Economy> registration = server.getServicesManager().getRegistration(Economy.class);
        assertNotNull(registration);
        assertEquals("SodaEconomy", registration.getProvider().getName());

        VaultIntegrationManager replacement = new VaultIntegrationManager(plugin, plugin.getTransactionService(),
                plugin.getPlayerIdentityApi(), plugin.getEconomyManager(), plugin.getLanguageManager(),
                new VaultIntegrationSettings(true, 3_000L, 100L));
        assertEquals(1, server.getServicesManager().getRegistrations(Economy.class).size(),
                "Repeated initialization must replace the stale SodaEconomy provider instead of stacking registrations.");

        replacement.close();
        integration.close();
        assertNull(server.getServicesManager().getRegistration(Economy.class));
    }

    private VaultEconomyProvider provider(VaultTransactionGateway gateway) {
        return provider(gateway, new VaultIntegrationSettings(true, 3_000L, 100L));
    }

    private VaultEconomyProvider provider(VaultTransactionGateway gateway, VaultIntegrationSettings settings) {
        PlayerIdentityApi identities = plugin.getPlayerIdentityApi();
        return new VaultEconomyProvider(plugin, gateway, identities, plugin.getEconomyManager(),
                plugin.getLanguageManager(), settings);
    }

    private static final class QueuedGateway implements VaultTransactionGateway {
        private final DurableOperation<TransactionResult> pending = new DurableOperation<>();
        private int committedMutations;
        @Override public DurableOperation<TransactionResult> deposit(UUID playerId, double amount,
                                                                      Map<String, String> metadata,
                                                                      Duration localAdmissionTimeout) {
            return pending;
        }
        @Override public DurableOperation<TransactionResult> withdraw(UUID playerId, double amount,
                                                                       Map<String, String> metadata,
                                                                       Duration localAdmissionTimeout) {
            return pending;
        }
        @Override public CompletableFuture<WalletAccountLookup> lookup(UUID playerId) {
            return CompletableFuture.completedFuture(new WalletAccountLookup(false, 0L));
        }
        @Override public DurableOperation<WalletAccountState> createAccount(UUID playerId,
                                                                            Duration localAdmissionTimeout) {
            return new DurableOperation<>();
        }
    }
}
