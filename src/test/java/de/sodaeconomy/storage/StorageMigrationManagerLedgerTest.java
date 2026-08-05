package de.sodaeconomy.storage;

import de.sodaeconomy.support.InMemoryStorage;
import de.sodaeconomy.support.MockBukkitTestBase;
import de.sodaeconomy.transaction.TransactionOrigin;
import de.sodaeconomy.transaction.TransactionRecord;
import de.sodaeconomy.transaction.TransactionType;
import de.sodaeconomy.transaction.WalletTransactionRequest;
import de.sodaeconomy.transaction.WalletOperation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageMigrationManagerLedgerTest extends MockBukkitTestBase {

    @Test
    void migratesBalancesBankBalancesAndImmutableWalletJournalTogether() throws Exception {
        UUID playerId = UUID.randomUUID();
        InMemoryStorage source = new InMemoryStorage();
        InMemoryStorage target = new InMemoryStorage();
        source.ensureWalletAccount(playerId, 1_000L, Instant.now());
        TransactionRecord credit = source.executeWalletTransaction(new WalletTransactionRequest(UUID.randomUUID(),
                Instant.now(), TransactionType.API_DEPOSIT, TransactionOrigin.api("MigrationTest"),
                WalletOperation.CREDIT, null, playerId, 250L, null, "Migration test credit", Map.of(),
                null, null, null), 0L);
        source.setBankBalance(playerId, 5D);

        StorageMigrationManager migration = new StorageMigrationManager(plugin);
        ExactStorageSnapshot snapshot = migration.readSnapshot(source, StorageType.YAML);
        migration.importSnapshot(target, snapshot, StorageType.SQLITE);

        assertEquals(Map.of(playerId, 12.50D), target.getAllBalances());
        assertEquals(Map.of(playerId, 5D), target.getAllBankBalances());
        assertEquals(Map.of(playerId, 1_250L), snapshot.balances());
        assertEquals(Map.of(playerId, 500L), snapshot.bankBalances());
        assertEquals(snapshot.walletTransactions(), target.getAllWalletTransactions());
        assertTrue(target.findTransaction(credit.id()).isPresent());
    }
}
