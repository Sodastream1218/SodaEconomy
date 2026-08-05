package de.sodaeconomy.storage;

import org.bukkit.plugin.java.JavaPlugin;

import de.sodaeconomy.identity.PlayerIdentity;
import de.sodaeconomy.transaction.TransactionRecord;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Coordinates source snapshots and atomic target imports after the target was verified. */
final class StorageMigrationManager {
    private final JavaPlugin plugin;

    StorageMigrationManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    ExactStorageSnapshot readSnapshot(Storage source, StorageType sourceType) throws Exception {
        Map<UUID, Long> balances = source.getAllBalanceMinorUnits();
        Map<UUID, Long> bankBalances = source.getAllBankBalanceMinorUnits();
        List<TransactionRecord> transactions = source instanceof WalletTransactionStore transactionStore
                ? transactionStore.getAllWalletTransactions()
                : List.of();
        Map<UUID, PlayerIdentity> identities = source.getAllPlayerIdentities();
        ExactStorageSnapshot snapshot = new ExactStorageSnapshot(balances, bankBalances, transactions, identities);
        plugin.getLogger().info("[Storage] Loaded " + snapshot.balances().size() + " main and "
                + snapshot.bankBalances().size() + " bank balance(s), " + snapshot.walletTransactions().size()
                + " wallet transaction(s), and " + snapshot.playerIdentities().size()
                + " player identity record(s) from " + sourceType + ".");
        return snapshot;
    }

    void importSnapshot(Storage target, ExactStorageSnapshot snapshot, StorageType targetType) throws Exception {
        target.replaceMinorUnitSnapshot(snapshot.balances(), snapshot.bankBalances(), snapshot.walletTransactions(),
                snapshot.playerIdentities());
        plugin.getLogger().info("[Storage] Imported " + snapshot.balances().size() + " main and "
                + snapshot.bankBalances().size() + " bank balance(s), " + snapshot.walletTransactions().size()
                + " wallet transaction(s), and " + snapshot.playerIdentities().size()
                + " player identity record(s) into " + targetType + ".");
    }
}
