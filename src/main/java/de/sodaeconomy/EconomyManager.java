package de.sodaeconomy;

import de.sodaeconomy.identity.PlayerIdentityApi;
import de.sodaeconomy.storage.ConfigManager;
import de.sodaeconomy.storage.RuntimeConfigSnapshot;
import de.sodaeconomy.storage.Storage;
import de.sodaeconomy.storage.WalletTransactionStore;
import de.sodaeconomy.transaction.TransactionOrigin;
import de.sodaeconomy.transaction.TransactionService;
import de.sodaeconomy.transaction.TransactionType;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Coordinates all main-account mutations and serializes compound economy transactions. */
public class EconomyManager {
    private final Storage storage;
    private final SodaEconomy plugin;
    private final TransactionService transactionService;
    private final ConfigManager configManager;
    private final PlayerIdentityApi playerIdentityApi;
    private final double startingBalance;
    private final double maxBalance;
    private final ReentrantLock transactionLock = new ReentrantLock(true);

    public EconomyManager(Storage storage, boolean debug, SodaEconomy plugin) {
        this(storage, debug, plugin, createTransactionService(storage, plugin));
    }

    public EconomyManager(Storage storage, boolean debug, SodaEconomy plugin, TransactionService transactionService) {
        this(storage, debug, plugin, transactionService, plugin.getPlayerIdentityApi());
    }

    public EconomyManager(Storage storage, boolean debug, SodaEconomy plugin, TransactionService transactionService,
                          PlayerIdentityApi playerIdentityApi) {
        this.storage = storage; this.plugin = plugin;
        this.transactionService = transactionService;
        this.playerIdentityApi = playerIdentityApi;
        this.configManager = plugin.getConfigManager();
        startingBalance = Money.normalize(configManager.getStartingBalance());
        if (startingBalance < 0) throw new IllegalArgumentException("balance must not be negative");
        maxBalance = configManager.getMaxBalance();
        if (!Money.isValid(maxBalance)) throw new IllegalArgumentException("max_balance must be finite");
    }

    public double getBalance(UUID uuid) { return transactionService.getBalanceSynchronously(uuid); }

    /** Legacy compatibility facade; new integrations should use {@link de.sodaeconomy.transaction.EconomyTransactionApi}. */
    @Deprecated(since = "1.1", forRemoval = true)
    public void setBalance(UUID uuid, double amount) {
        if (!Money.isValid(amount) || amount < 0D || uuid == null) return;
        double boundedAmount = maxBalance > 0D ? Math.min(amount, maxBalance) : amount;
        transactionService.setBalanceSynchronously(uuid, boundedAmount, TransactionType.API_SET,
                TransactionOrigin.system("Legacy EconomyManager"), "Legacy balance set", Map.of());
    }

    /** Legacy compatibility facade; new integrations should use {@link de.sodaeconomy.transaction.EconomyTransactionApi}. */
    @Deprecated(since = "1.1", forRemoval = true)
    public void addBalance(UUID uuid, double amount) {
        if (!Money.isPositive(amount) || uuid == null) return;
        transactionService.depositSynchronously(uuid, amount, TransactionType.API_DEPOSIT,
                TransactionOrigin.system("Legacy EconomyManager"), "Legacy balance credit", Map.of());
    }

    /** Legacy compatibility facade; new integrations should use {@link de.sodaeconomy.transaction.EconomyTransactionApi}. */
    @Deprecated(since = "1.1", forRemoval = true)
    public boolean removeBalance(UUID uuid, double amount) {
        if (!Money.isPositive(amount) || uuid == null) return false;
        return transactionService.withdrawSynchronously(uuid, amount, TransactionType.API_WITHDRAW,
                TransactionOrigin.system("Legacy EconomyManager"), "Legacy balance debit", Map.of()).isSuccessful();
    }

    /** Pays either the complete amount or nothing. The maximum balance is checked under the same lock. */
    /** Legacy compatibility facade; new integrations should use {@link de.sodaeconomy.transaction.EconomyTransactionApi}. */
    @Deprecated(since = "1.1", forRemoval = true)
    public boolean transfer(UUID source, UUID target, double amount) {
        if (source == null || target == null || source.equals(target) || !Money.isPositive(amount)) return false;
        return transactionService.transferSynchronously(source, target, amount, TransactionType.API_TRANSFER,
                TransactionOrigin.system("Legacy EconomyManager"), "Legacy wallet transfer", Map.of()).isSuccessful();
    }

    /** Atomically moves money between a player's main and bank account. */
    /** Legacy compatibility facade; new integrations should use {@link de.sodaeconomy.transaction.EconomyTransactionApi}. */
    @Deprecated(since = "1.1", forRemoval = true)
    public boolean transferMainAndBank(UUID uuid, boolean mainToBank, double amount) {
        if (uuid == null || !Money.isPositive(amount)) return false;
        return locked(() -> transactionService.transferWalletAndBankSynchronously(uuid, mainToBank, amount,
                TransactionOrigin.system("Banking"), mainToBank ? "Bank deposit" : "Bank withdrawal", Map.of())
                .isSuccessful(), false);
    }

    public void resetBalance(UUID uuid) { setBalance(uuid, startingBalance); }
    public Map<UUID, Double> getAllBalances() { return locked(storage::getAllBalances, Map.of()); }
    public String getPlayerName(UUID uuid) {
        if (uuid == null) return "Unknown";
        return playerIdentityApi == null
                ? "Unknown-" + uuid.toString().substring(0, 8)
                : playerIdentityApi.cachedDisplayName(uuid);
    }
    public String formatCurrency(double amount) {
        long minor = Money.isValid(amount) ? Money.toMinorUnits(amount) : 0L;
        return formatCurrencyMinor(minor);
    }

    /** Formats exact minor units through the same live currency configuration used by commands. */
    public String formatCurrencyMinor(long amountMinor) {
        return CurrencyFormatter.formatMinorUnits(amountMinor, configManager.getRuntimeSettings().currency());
    }
    public double getStartingBalance() { return startingBalance; }
    public double getMaxBalance() { return maxBalance; }
    public String getCurrencySymbol() { return configManager.getCurrencySymbol(); }
    public boolean isDisplayAfterAmount() { return configManager.isCurrencyDisplayAfterAmount(); }
    TransactionService transactionService() { return transactionService; }

    /** Internal command-service access retained temporarily for source compatibility. */
    @Deprecated(since = "1.1", forRemoval = true)
    public TransactionService getTransactionService() { return transactionService; }

    public <T> T locked(ThrowingSupplier<T> action, T fallback) {
        transactionLock.lock();
        try { return action.get(); }
        catch (Exception exception) { plugin.getLogger().warning("Economy storage operation failed: " + exception.getMessage()); return fallback; }
        finally { transactionLock.unlock(); }
    }

    private static TransactionService createTransactionService(Storage storage, SodaEconomy plugin) {
        if (!(storage instanceof WalletTransactionStore transactionStore)) {
            throw new IllegalArgumentException("The configured storage does not support wallet transactions");
        }
        return new TransactionService(plugin, transactionStore, plugin.getConfigManager().getStartingBalance(),
                plugin.getConfigManager().getMaxBalance());
    }
    @FunctionalInterface public interface ThrowingSupplier<T> { T get() throws Exception; }
}
