package de.sodaeconomy;

import de.sodaeconomy.storage.Storage;
import de.sodaeconomy.transaction.TransactionOrigin;
import de.sodaeconomy.transaction.TransactionResult;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Bank facade that delegates persistent account coordination to the central transaction service. */
public class BankManager {
    private final EconomyManager economyManager;
    private final SodaEconomy plugin;
    private final boolean debug;

    public BankManager(Storage storage, EconomyManager economyManager, SodaEconomy plugin, boolean debug) {
        Objects.requireNonNull(storage, "storage");
        this.economyManager = Objects.requireNonNull(economyManager, "economyManager");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.debug = debug;
    }

    public double getBankBalance(UUID uuid) {
        return economyManager.transactionService().getBankBalanceSynchronously(uuid);
    }

    /** Retrieves a bank balance without blocking the Paper main thread. */
    public CompletableFuture<Double> getBankBalanceAsynchronously(UUID uuid) {
        return economyManager.transactionService().getBankBalanceAsynchronously(uuid);
    }

    /**
     * Retained for source compatibility. Call {@link #trySetBankBalance(UUID, double)} when the
     * caller needs to report a failed persistence operation.
     */
    public void setBankBalance(UUID uuid, double amount) {
        trySetBankBalance(uuid, amount);
    }

    /**
     * Updates a bank-only balance through the central service lock and returns whether the
     * storage update completed. Bank-only administrative changes are intentionally outside the
     * wallet ledger until bank-ledger records are introduced.
     */
    public boolean trySetBankBalance(UUID uuid, double amount) {
        return economyManager.transactionService().setBankBalanceSynchronously(uuid, amount);
    }
    public boolean deposit(UUID uuid, double amount) {
        return depositTransaction(uuid, amount, TransactionOrigin.system("Banking")).isSuccessful();
    }

    /** Executes an auditable wallet-to-bank transfer through the central transaction service. */
    public TransactionResult depositTransaction(UUID uuid, double amount, TransactionOrigin origin) {
        TransactionResult result = economyManager.transactionService().transferWalletAndBankSynchronously(uuid, true,
                amount, origin, "Bank deposit", Map.of("operation", "bank_deposit"));
        boolean successful = result.isSuccessful();
        if (debug) plugin.debugBanking("Deposit " + (successful ? "completed" : "rejected") + " for " + uuid + ".");
        return result;
    }

    /** Executes an auditable wallet-to-bank movement asynchronously. */
    public CompletableFuture<TransactionResult> depositTransactionAsynchronously(UUID uuid, double amount,
                                                                                  TransactionOrigin origin) {
        return economyManager.transactionService().transferWalletAndBankAsynchronously(uuid, true, amount, origin,
                "Bank deposit", Map.of("operation", "bank_deposit")).thenApply(result -> {
            if (debug) plugin.debugBanking("Deposit " + (result.isSuccessful() ? "completed" : "rejected")
                    + " for " + uuid + ".");
            return result;
        });
    }
    public boolean withdraw(UUID uuid, double amount) {
        return withdrawTransaction(uuid, amount, TransactionOrigin.system("Banking")).isSuccessful();
    }

    /** Executes an auditable bank-to-wallet transfer through the central transaction service. */
    public TransactionResult withdrawTransaction(UUID uuid, double amount, TransactionOrigin origin) {
        TransactionResult result = economyManager.transactionService().transferWalletAndBankSynchronously(uuid, false,
                amount, origin, "Bank withdrawal", Map.of("operation", "bank_withdrawal"));
        boolean successful = result.isSuccessful();
        if (debug) plugin.debugBanking("Withdrawal " + (successful ? "completed" : "rejected") + " for " + uuid + ".");
        return result;
    }

    /** Executes an auditable bank-to-wallet movement asynchronously. */
    public CompletableFuture<TransactionResult> withdrawTransactionAsynchronously(UUID uuid, double amount,
                                                                                   TransactionOrigin origin) {
        return economyManager.transactionService().transferWalletAndBankAsynchronously(uuid, false, amount, origin,
                "Bank withdrawal", Map.of("operation", "bank_withdrawal")).thenApply(result -> {
            if (debug) plugin.debugBanking("Withdrawal " + (result.isSuccessful() ? "completed" : "rejected")
                    + " for " + uuid + ".");
            return result;
        });
    }
    /** Retained for source compatibility; use {@link #tryAdminSetBankBalance(UUID, double)} for a result. */
    public void adminSetBankBalance(UUID uuid, double amount) { tryAdminSetBankBalance(uuid, amount); }

    /** Performs a bank-only administrative update and exposes storage failure to the command layer. */
    public boolean tryAdminSetBankBalance(UUID uuid, double amount) { return trySetBankBalance(uuid, amount); }

    /** Updates a bank-only administrative balance without blocking the Paper main thread. */
    public CompletableFuture<Boolean> tryAdminSetBankBalanceAsynchronously(UUID uuid, double amount) {
        return economyManager.transactionService().setBankBalanceAsynchronously(uuid, amount);
    }
    public Map<UUID, Double> getAllBankBalances() {
        return economyManager.transactionService().getAllBankBalancesSynchronously();
    }

    public int applyInterest(double rate, double maximumInterest) {
        return applyInterest(rate, maximumInterest, Duration.ZERO);
    }

    public int applyInterest(double rate, double maximumInterest, Duration minimumInterval) {
        int changed = economyManager.transactionService().applyBankInterestSynchronously(rate, maximumInterest,
                minimumInterval);
        if (debug) plugin.debugBanking("Interest applied to " + changed + " bank account(s).");
        return changed;
    }
}
