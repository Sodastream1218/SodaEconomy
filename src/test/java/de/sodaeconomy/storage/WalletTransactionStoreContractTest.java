package de.sodaeconomy.storage;

import de.sodaeconomy.Money;
import de.sodaeconomy.support.MockBukkitTestBase;
import de.sodaeconomy.transaction.EconomyAnalytics;
import de.sodaeconomy.transaction.PlayerTransactionStatistics;
import de.sodaeconomy.transaction.TransactionFailureReason;
import de.sodaeconomy.transaction.TransactionOrigin;
import de.sodaeconomy.transaction.TransactionRecord;
import de.sodaeconomy.transaction.TransactionStatus;
import de.sodaeconomy.transaction.TransactionType;
import de.sodaeconomy.transaction.WalletOperation;
import de.sodaeconomy.transaction.WalletTransactionRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shared atomic-journal contract for every production wallet transaction store. */
abstract class WalletTransactionStoreContractTest extends MockBukkitTestBase {
    private Storage storage;
    private WalletTransactionStore transactionStore;

    @BeforeEach
    final void initializeTransactionStore() throws Exception {
        configureStorage();
        storage = createStorage();
        storage.init(plugin);
        transactionStore = (WalletTransactionStore) storage;
    }

    @AfterEach
    final void closeTransactionStore() {
        if (storage != null) {
            storage.close();
        }
    }

    protected void configureStorage() {
    }

    protected abstract Storage createStorage();

    @Test
    void createsExactlyOneInitialEntryForANewWalletAccount() throws Exception {
        UUID playerId = UUID.randomUUID();

        assertTrue(transactionStore.ensureWalletAccount(playerId, 5_000L, Instant.now()).created());
        assertFalse(transactionStore.ensureWalletAccount(playerId, 99_999L, Instant.now()).created());

        List<TransactionRecord> records = transactionStore.getAllWalletTransactions().stream()
                .filter(record -> playerId.equals(record.targetPlayerId()))
                .filter(record -> record.type() == TransactionType.INITIAL_BALANCE)
                .toList();
        assertEquals(1, records.size());
        assertEquals(5_000L, records.get(0).appliedAmountMinor());
        assertEquals(50D, storage.getBalance(playerId));
    }

    @Test
    void returnsAnEmptyResultForANullOrUnknownJournalIdentifier() throws Exception {
        assertTrue(transactionStore.findTransaction(null).isEmpty());
        assertTrue(transactionStore.findTransaction(UUID.randomUUID()).isEmpty());
    }

    @Test
    void commitsWalletTransfersAndFailureRecordsAtomically() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        transactionStore.ensureWalletAccount(sourceId, 1_000L, Instant.now());
        transactionStore.ensureWalletAccount(targetId, 0L, Instant.now());

        TransactionRecord successful = transactionStore.executeWalletTransaction(transfer(sourceId, targetId, 250L), 0L);
        TransactionRecord failed = transactionStore.executeWalletTransaction(transfer(sourceId, targetId, 1_000L), 0L);

        assertTrue(successful.isSuccessful());
        assertEquals(TransactionStatus.FAILED, failed.status());
        assertEquals(TransactionFailureReason.INSUFFICIENT_FUNDS, failed.failureReason());
        assertEquals(7.50D, storage.getBalance(sourceId));
        assertEquals(2.50D, storage.getBalance(targetId));
        assertEquals(successful, transactionStore.findTransaction(successful.id()).orElseThrow());
        assertEquals(failed, transactionStore.findTransaction(failed.id()).orElseThrow());
    }

    @Test
    void commitsWalletBankMovesAndTheirJournalEntriesAtomically() throws Exception {
        UUID playerId = UUID.randomUUID();
        transactionStore.ensureWalletAccount(playerId, 1_000L, Instant.now());

        TransactionRecord deposit = transactionStore.executeWalletBankTransaction(walletToBank(playerId, 250L), true, 0L);
        TransactionRecord withdrawal = transactionStore.executeWalletBankTransaction(bankToWallet(playerId, 100L), false, 0L);
        TransactionRecord rejected = transactionStore.executeWalletBankTransaction(bankToWallet(playerId, 200L), false, 0L);

        assertTrue(deposit.isSuccessful());
        assertEquals(TransactionType.WALLET_TO_BANK, deposit.type());
        assertTrue(withdrawal.isSuccessful());
        assertEquals(TransactionType.BANK_TO_WALLET, withdrawal.type());
        assertEquals(TransactionStatus.FAILED, rejected.status());
        assertEquals(TransactionFailureReason.INSUFFICIENT_FUNDS, rejected.failureReason());
        assertEquals(8.50D, storage.getBalance(playerId));
        assertEquals(1.50D, storage.getBankBalance(playerId));
    }

    @Test
    void createsAnImmutableRollbackAndRejectsADuplicateSuccessfulRollback() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        transactionStore.ensureWalletAccount(sourceId, 1_000L, Instant.now());
        transactionStore.ensureWalletAccount(targetId, 0L, Instant.now());
        TransactionRecord original = transactionStore.executeWalletTransaction(transfer(sourceId, targetId, 250L), 0L);

        TransactionRecord rollback = transactionStore.executeWalletTransaction(rollback(original, targetId, sourceId), 0L);
        TransactionRecord duplicate = transactionStore.executeWalletTransaction(rollback(original, targetId, sourceId), 0L);

        assertTrue(original.isSuccessful());
        assertTrue(rollback.isSuccessful());
        assertEquals(original.id(), rollback.reversalOfTransactionId());
        assertEquals(TransactionStatus.FAILED, duplicate.status());
        assertEquals(TransactionFailureReason.DUPLICATE_ROLLBACK, duplicate.failureReason());
        assertEquals(10D, storage.getBalance(sourceId));
        assertEquals(0D, storage.getBalance(targetId));
        assertEquals(original, transactionStore.findTransaction(original.id()).orElseThrow());
    }

    @Test
    void replacesMigrationSnapshotsWithoutDroppingWalletJournalEntries() throws Exception {
        UUID playerId = UUID.randomUUID();
        TransactionRecord record = new TransactionRecord(UUID.randomUUID(), Instant.now(), TransactionType.API_DEPOSIT,
                TransactionStatus.SUCCESS, TransactionOrigin.api("ContractTest"), WalletOperation.CREDIT,
                null, playerId, 1_000L, 1_000L, null, null, 0L, 1_000L,
                "Snapshot journal", Map.of("test", "snapshot"), null, null, null,
                TransactionFailureReason.NONE, null);

        storage.replaceSnapshot(new StorageSnapshot(Map.of(playerId, Money.fromMinorUnits(1_000L)), Map.of(),
                List.of(record)));

        assertEquals(10D, storage.getBalance(playerId));
        assertEquals(record, transactionStore.findTransaction(record.id()).orElseThrow());
    }

    @Test
    void exportsTheWalletJournalInDeterministicChronologicalOrder() throws Exception {
        UUID playerId = UUID.randomUUID();
        Instant firstTimestamp = Instant.parse("2026-01-01T00:00:00Z");
        TransactionRecord later = new TransactionRecord(UUID.randomUUID(), firstTimestamp.plusSeconds(1),
                TransactionType.ADMIN_GIVE, TransactionStatus.SUCCESS, TransactionOrigin.console(),
                WalletOperation.CREDIT, null, playerId, 500L, 500L,
                null, null, 1_000L, 1_500L, "Later snapshot journal", Map.of(),
                null, null, null, TransactionFailureReason.NONE, null);
        TransactionRecord earlier = new TransactionRecord(UUID.randomUUID(), firstTimestamp,
                TransactionType.API_DEPOSIT, TransactionStatus.SUCCESS, TransactionOrigin.api("ContractTest"),
                WalletOperation.CREDIT, null, playerId, 1_000L, 1_000L,
                null, null, 0L, 1_000L, "Earlier snapshot journal", Map.of(),
                null, null, null, TransactionFailureReason.NONE, null);

        storage.replaceSnapshot(new StorageSnapshot(Map.of(playerId, 15D), Map.of(), List.of(later, earlier)));

        assertEquals(List.of(earlier, later), transactionStore.getAllWalletTransactions());
    }

    @Test
    void calculatesExtendedAndParticipantAnalyticsFromTheImmutableJournal() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        transactionStore.ensureWalletAccount(sourceId, 1_000L, Instant.now());
        transactionStore.ensureWalletAccount(targetId, 0L, Instant.now());
        transactionStore.executeWalletTransaction(transfer(sourceId, targetId, 250L), 0L);

        EconomyAnalytics analytics = transactionStore.getEconomyAnalytics();
        PlayerTransactionStatistics targetStatistics = transactionStore.getPlayerTransactionStatistics(targetId);

        assertEquals(2L, analytics.statistics().walletAccountCount());
        assertEquals(3L, analytics.statistics().totalTransactionCount());
        assertEquals(1_000L, analytics.largestTransactionMinor());
        assertEquals(250L, analytics.totalTransferVolumeMinor());
        assertEquals(2L, targetStatistics.transactionCount());
        assertEquals(250L, targetStatistics.totalReceivedMinor());
        assertEquals(0L, targetStatistics.totalSentMinor());
    }

    private static WalletTransactionRequest transfer(UUID sourceId, UUID targetId, long amountMinor) {
        return new WalletTransactionRequest(UUID.randomUUID(), Instant.now(), TransactionType.API_TRANSFER,
                TransactionOrigin.api("ContractTest"), WalletOperation.TRANSFER, sourceId, targetId, amountMinor,
                null, "Contract transfer", Map.of(), null, null, null);
    }

    private static WalletTransactionRequest walletToBank(UUID playerId, long amountMinor) {
        return new WalletTransactionRequest(UUID.randomUUID(), Instant.now(), TransactionType.WALLET_TO_BANK,
                TransactionOrigin.system("ContractTest"), WalletOperation.DEBIT, playerId, null, amountMinor,
                null, "Contract bank deposit", Map.of(), null, null, null);
    }

    private static WalletTransactionRequest bankToWallet(UUID playerId, long amountMinor) {
        return new WalletTransactionRequest(UUID.randomUUID(), Instant.now(), TransactionType.BANK_TO_WALLET,
                TransactionOrigin.system("ContractTest"), WalletOperation.CREDIT, null, playerId, amountMinor,
                null, "Contract bank withdrawal", Map.of(), null, null, null);
    }

    private static WalletTransactionRequest rollback(TransactionRecord original, UUID sourceId, UUID targetId) {
        return new WalletTransactionRequest(UUID.randomUUID(), Instant.now(), TransactionType.ROLLBACK,
                TransactionOrigin.console(), WalletOperation.TRANSFER, sourceId, targetId,
                original.appliedAmountMinor(), null, "Contract rollback", Map.of(), original.id(), null, null);
    }
}
