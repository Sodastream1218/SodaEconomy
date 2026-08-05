package de.sodaeconomy.transaction;

import be.seeseemelk.mockbukkit.MockBukkit;
import de.sodaeconomy.SodaEconomy;
import de.sodaeconomy.storage.Storage;
import de.sodaeconomy.storage.WalletTransactionStore;
import de.sodaeconomy.support.InMemoryStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneOffset.UTC);
    private static final TransactionOrigin CONSOLE = TransactionOrigin.console();

    private SodaEconomy plugin;
    private InMemoryStorage storage;
    private TransactionService transactions;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.load(SodaEconomy.class);
        storage = new InMemoryStorage();
        transactions = new TransactionService(plugin, storage, 1_000D, 0D, CLOCK);
    }

    @AfterEach
    void tearDown() {
        if (transactions != null) {
            transactions.close();
        }
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void createsAndJournalsTheInitialBalanceExactlyOnce() {
        UUID playerId = UUID.randomUUID();

        assertEquals(1_000D, transactions.getBalanceSynchronously(playerId));
        assertEquals(1_000D, transactions.getBalanceSynchronously(playerId));

        var accountHistory = storage.findTransactions(playerHistory(playerId, 10));
        assertEquals(1, accountHistory.records().size());
        TransactionRecord initialRecord = accountHistory.records().get(0);
        assertEquals(TransactionType.INITIAL_BALANCE, initialRecord.type());
        assertEquals(TransactionStatus.SUCCESS, initialRecord.status());
        assertEquals(playerId, initialRecord.targetPlayerId());
        assertEquals(100_000L, initialRecord.appliedAmountMinor());
        assertEquals(1_000D, storage.getBalance(playerId));
    }

    @Test
    void commitsTransferAndJournalRecordTogether() {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        setBalance(sourceId, 500D);
        setBalance(targetId, 50D);

        TransactionResult result = transactions.transferSynchronously(sourceId, targetId, 125D,
                TransactionType.PLAYER_TRANSFER, CONSOLE, "Test transfer", Map.of("test", "transfer"));

        assertTrue(result.isSuccessful());
        assertEquals(375D, transactions.getBalanceSynchronously(sourceId));
        assertEquals(175D, transactions.getBalanceSynchronously(targetId));
        TransactionRecord record = result.transaction();
        assertEquals(TransactionStatus.SUCCESS, record.status());
        assertEquals(WalletOperation.TRANSFER, record.operation());
        assertEquals(TransactionType.PLAYER_TRANSFER, record.type());
        assertEquals(sourceId, record.sourcePlayerId());
        assertEquals(targetId, record.targetPlayerId());
        assertEquals(12_500L, record.appliedAmountMinor());
        assertEquals(50_000L, record.sourceBalanceBeforeMinor());
        assertEquals(37_500L, record.sourceBalanceAfterMinor());
        assertEquals(5_000L, record.targetBalanceBeforeMinor());
        assertEquals(17_500L, record.targetBalanceAfterMinor());
        assertEquals(record, storage.findTransaction(record.id()).orElseThrow());
    }

    @Test
    void journalsFailedInsufficientFundsTransferWithoutMutatingBalances() {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        setBalance(sourceId, 10D);
        setBalance(targetId, 20D);

        TransactionResult result = transactions.transferSynchronously(sourceId, targetId, 11D,
                TransactionType.PLAYER_TRANSFER, CONSOLE, "Insufficient funds test", Map.of());

        assertFalse(result.isSuccessful());
        assertEquals(TransactionFailureReason.INSUFFICIENT_FUNDS, result.failureReason());
        assertNotNull(result.transaction());
        assertEquals(TransactionStatus.FAILED, result.transaction().status());
        assertEquals(TransactionFailureReason.INSUFFICIENT_FUNDS, result.transaction().failureReason());
        assertEquals(0L, result.transaction().appliedAmountMinor());
        assertEquals(10D, transactions.getBalanceSynchronously(sourceId));
        assertEquals(20D, transactions.getBalanceSynchronously(targetId));
        assertEquals(result.transaction(), storage.findTransaction(result.transaction().id()).orElseThrow());
    }

    @Test
    void rollbackCreatesAnImmutableInverseAndRejectsDuplicateRollback() {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        setBalance(sourceId, 300D);
        setBalance(targetId, 100D);
        TransactionResult original = transactions.transferSynchronously(sourceId, targetId, 75D,
                TransactionType.PLAYER_TRANSFER, CONSOLE, "Original transfer", Map.of());

        assertTrue(original.isSuccessful());

        TransactionResult rollback = transactions.rollbackSynchronously(original.transaction().id(), CONSOLE, "Correction");

        assertTrue(rollback.isSuccessful());
        assertEquals(300D, transactions.getBalanceSynchronously(sourceId));
        assertEquals(100D, transactions.getBalanceSynchronously(targetId));
        assertEquals(original.transaction(), storage.findTransaction(original.transaction().id()).orElseThrow());
        assertNotEquals(original.transaction().id(), rollback.transaction().id());
        assertEquals(TransactionType.ROLLBACK, rollback.transaction().type());
        assertEquals(WalletOperation.TRANSFER, rollback.transaction().operation());
        assertEquals(original.transaction().id(), rollback.transaction().reversalOfTransactionId());
        assertEquals(targetId, rollback.transaction().sourcePlayerId());
        assertEquals(sourceId, rollback.transaction().targetPlayerId());

        TransactionResult duplicate = transactions.rollbackSynchronously(original.transaction().id(), CONSOLE, "Duplicate");

        assertFalse(duplicate.isSuccessful());
        assertEquals(TransactionFailureReason.DUPLICATE_ROLLBACK, duplicate.failureReason());
        assertEquals(TransactionStatus.FAILED, duplicate.transaction().status());
        assertEquals(300D, transactions.getBalanceSynchronously(sourceId));
        assertEquals(100D, transactions.getBalanceSynchronously(targetId));
    }

    @Test
    void commitsWalletBankMovementsAndJournalRecordsTogether() {
        UUID playerId = UUID.randomUUID();
        transactions.getBalanceSynchronously(playerId);

        TransactionResult deposit = transactions.transferWalletAndBankSynchronously(playerId, true, 125D,
                CONSOLE, "Bank deposit", Map.of());
        TransactionResult withdrawal = transactions.transferWalletAndBankSynchronously(playerId, false, 25D,
                CONSOLE, "Bank withdrawal", Map.of());
        TransactionResult rejectedWithdrawal = transactions.transferWalletAndBankSynchronously(playerId, false, 101D,
                CONSOLE, "Rejected withdrawal", Map.of());

        assertTrue(deposit.isSuccessful());
        assertEquals(TransactionType.WALLET_TO_BANK, deposit.transaction().type());
        assertEquals(WalletOperation.DEBIT, deposit.transaction().operation());
        assertTrue(withdrawal.isSuccessful());
        assertEquals(TransactionType.BANK_TO_WALLET, withdrawal.transaction().type());
        assertEquals(WalletOperation.CREDIT, withdrawal.transaction().operation());
        assertFalse(rejectedWithdrawal.isSuccessful());
        assertEquals(TransactionFailureReason.INSUFFICIENT_FUNDS, rejectedWithdrawal.failureReason());
        assertEquals(900D, transactions.getBalanceSynchronously(playerId));
        assertEquals(100D, storage.getBankBalance(playerId));
        assertEquals(TransactionStatus.FAILED, rejectedWithdrawal.transaction().status());
        assertEquals(4, storage.getAllWalletTransactions().size());
    }

    @Test
    void returnsPaginatedPlayerHistoryAndAccurateStatistics() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        transactions.getBalanceSynchronously(sourceId);
        transactions.getBalanceSynchronously(targetId);
        assertTrue(transactions.transferSynchronously(sourceId, targetId, 25D,
                TransactionType.PLAYER_TRANSFER, CONSOLE, "Transfer", Map.of()).isSuccessful());
        assertTrue(transactions.depositSynchronously(targetId, 10D,
                TransactionType.API_DEPOSIT, TransactionOrigin.api("TransactionServiceTest"), "Deposit", Map.of()).isSuccessful());
        assertTrue(transactions.withdrawSynchronously(targetId, 5D,
                TransactionType.API_WITHDRAW, TransactionOrigin.api("TransactionServiceTest"), "Withdrawal", Map.of()).isSuccessful());
        assertFalse(transactions.withdrawSynchronously(targetId, 2_000D,
                TransactionType.API_WITHDRAW, TransactionOrigin.api("TransactionServiceTest"), "Failed withdrawal", Map.of()).isSuccessful());

        TransactionQuery firstPageQuery = playerHistory(targetId, 2);
        TransactionPage firstPage = transactions.findTransactions(firstPageQuery).get(5L, TimeUnit.SECONDS);
        TransactionPage secondPage = transactions.findTransactions(firstPageQuery.nextPage()).get(5L, TimeUnit.SECONDS);
        EconomyStatistics statistics = transactions.getStatistics().get(5L, TimeUnit.SECONDS);
        EconomyAnalytics analytics = transactions.getAnalytics().get(5L, TimeUnit.SECONDS);
        PlayerTransactionStatistics playerStatistics = transactions.getPlayerStatistics(targetId)
                .get(5L, TimeUnit.SECONDS);

        assertEquals(2, firstPage.records().size());
        assertTrue(firstPage.hasMore());
        assertFalse(secondPage.records().isEmpty());
        Set<UUID> firstPageIds = firstPage.records().stream().map(TransactionRecord::id).collect(java.util.stream.Collectors.toSet());
        assertTrue(secondPage.records().stream().map(TransactionRecord::id).noneMatch(firstPageIds::contains));
        assertTrue(firstPage.records().stream().allMatch(record -> targetId.equals(record.sourcePlayerId())
                || targetId.equals(record.targetPlayerId())));
        assertEquals(2L, statistics.walletAccountCount());
        assertEquals(200_500L, statistics.totalBalanceMinor());
        assertEquals(targetId, statistics.richestPlayerId());
        assertEquals(103_000L, statistics.richestBalanceMinor());
        assertEquals(6L, statistics.totalTransactionCount());
        assertEquals(5L, statistics.successfulTransactionCount());
        assertEquals(1L, statistics.failedTransactionCount());
        assertEquals(201_000L, statistics.totalCreditsMinor());
        assertEquals(500L, statistics.totalDebitsMinor());
        assertEquals(100_000L, analytics.largestTransactionMinor());
        assertEquals(2_500L, analytics.totalTransferVolumeMinor());
        assertEquals(targetId, playerStatistics.playerId());
        assertEquals(5L, playerStatistics.transactionCount());
        assertEquals(103_500L, playerStatistics.totalReceivedMinor());
        assertEquals(500L, playerStatistics.totalSentMinor());
        assertEquals(103_000L, playerStatistics.netChangeMinor());
    }

    @Test
    void completesPublicApiCallsAsynchronouslyWithPersistedResult() throws Exception {
        UUID playerId = UUID.randomUUID();

        TransactionResult result = transactions.deposit(playerId, 15D, TransactionType.API_DEPOSIT,
                TransactionOrigin.api("TransactionServiceTest"), "Async deposit", Map.of("mode", "async"))
                .get(5L, TimeUnit.SECONDS);

        assertTrue(result.isSuccessful());
        assertEquals(1_015D, transactions.getBalanceSynchronously(playerId));
        assertEquals(TransactionType.API_DEPOSIT, result.transaction().type());
        assertEquals("async", result.transaction().metadata().get("mode"));
        assertEquals(result.transaction(), storage.findTransaction(result.transaction().id()).orElseThrow());
    }

    @Test
    void rejectsAuditTypesThatDoNotDescribeTheRequestedOperation() throws Exception {
        UUID playerId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> new WalletTransactionRequest(UUID.randomUUID(), Instant.now(),
                TransactionType.API_DEPOSIT, CONSOLE, WalletOperation.DEBIT, playerId, null, 1L,
                null, "Invalid type", Map.of(), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new WalletTransactionRequest(UUID.randomUUID(), Instant.now(),
                TransactionType.INITIAL_BALANCE, CONSOLE, WalletOperation.CREDIT, null, playerId, 1L,
                null, "Forged opening balance", Map.of(), null, null, null));

        TransactionResult synchronous = transactions.depositSynchronously(playerId, 10D,
                TransactionType.WALLET_TO_BANK, CONSOLE, "Forged bank movement", Map.of());
        TransactionResult asynchronous = transactions.deposit(playerId, 10D,
                TransactionType.ADMIN_GIVE, CONSOLE, "Forged administrator grant", Map.of())
                .get(5L, TimeUnit.SECONDS);
        TransactionResult forgedOrigin = transactions.deposit(playerId, 10D,
                TransactionType.API_DEPOSIT, CONSOLE, "Forged console origin", Map.of())
                .get(5L, TimeUnit.SECONDS);

        assertEquals(TransactionFailureReason.INVALID_REQUEST, synchronous.failureReason());
        assertEquals(TransactionFailureReason.INVALID_REQUEST, asynchronous.failureReason());
        assertEquals(TransactionFailureReason.INVALID_REQUEST, forgedOrigin.failureReason());
        assertTrue(storage.getAllWalletTransactions().isEmpty());
    }

    @Test
    void keepsRollbackAmountsInExactMinorUnits() {
        long exactAmountMinor = 9_007_199_254_740_993L;
        UUID sourceId = UUID.randomUUID();
        TransactionRecord original = new TransactionRecord(UUID.randomUUID(), CLOCK.instant(), TransactionType.API_WITHDRAW,
                TransactionStatus.SUCCESS, CONSOLE, WalletOperation.DEBIT, sourceId, null,
                exactAmountMinor, exactAmountMinor, exactAmountMinor, 0L, null, null,
                "Large withdrawal", Map.of(), null, null, null, TransactionFailureReason.NONE, null);
        RollbackCapturingStorage capturingStorage = new RollbackCapturingStorage(original);

        try (TransactionService rollbackService = new TransactionService(plugin, capturingStorage, 0D, 0D, CLOCK)) {
            TransactionResult rollback = rollbackService.rollbackSynchronously(original.id(), CONSOLE, "Exact rollback");

            assertTrue(rollback.isSuccessful());
            assertNotNull(capturingStorage.capturedRequest);
            assertEquals(exactAmountMinor, capturingStorage.capturedRequest.amountMinor());
        }
    }

    @Test
    void returnsServiceStoppingInsteadOfThrowingAfterShutdown() throws Exception {
        transactions.close();

        TransactionResult result = transactions.deposit(UUID.randomUUID(), 10D, TransactionType.API_DEPOSIT,
                TransactionOrigin.api("TransactionServiceTest"), "After shutdown", Map.of())
                .get(5L, TimeUnit.SECONDS);

        assertEquals(TransactionFailureReason.SERVICE_STOPPING, result.failureReason());
    }

    private void setBalance(UUID playerId, double amount) {
        TransactionResult result = transactions.setBalanceSynchronously(playerId, amount, TransactionType.API_SET,
                CONSOLE, "Test setup", Map.of());
        assertTrue(result.isSuccessful());
    }

    private static TransactionQuery playerHistory(UUID playerId, int limit) {
        return new TransactionQuery(null, playerId, null, null, null, null, null, null, 0, limit);
    }

    /** Captures the exact rollback request without converting persisted minor units through doubles. */
    private static final class RollbackCapturingStorage implements Storage, WalletTransactionStore {
        private final TransactionRecord original;
        private WalletTransactionRequest capturedRequest;

        private RollbackCapturingStorage(TransactionRecord original) {
            this.original = original;
        }

        @Override public void init(JavaPlugin plugin) { }
        @Override public Double getBalance(UUID uuid) { return null; }
        @Override public double getOrCreateBalance(UUID uuid, double initialBalance) { return initialBalance; }
        @Override public void setBalance(UUID uuid, double amount) { }
        @Override public Map<UUID, Double> getAllBalances() { return Map.of(); }
        @Override public void saveAll(Map<UUID, Double> balances) { }
        @Override public Double getBankBalance(UUID uuid) { return null; }
        @Override public void setBankBalance(UUID uuid, double amount) { }
        @Override public Map<UUID, Double> getAllBankBalances() { return Map.of(); }
        @Override public void saveAllBank(Map<UUID, Double> bankBalances) { }
        @Override public boolean transferMain(UUID source, UUID target, double amount) { return false; }
        @Override public boolean transferMainAndBank(UUID uuid, boolean mainToBank, double amount) { return false; }
        @Override public void close() { }
        @Override public boolean isDebug() { return false; }

        @Override
        public WalletAccountState ensureWalletAccount(UUID playerId, long startingBalanceMinor, Instant timestamp) {
            return new WalletAccountState(0L, false);
        }

        @Override
        public TransactionRecord executeWalletTransaction(WalletTransactionRequest request, long maximumBalanceMinor) {
            capturedRequest = request;
            return TransactionRecords.successful(request, request.amountMinor(), null, null,
                    0L, request.amountMinor());
        }

        @Override
        public TransactionRecord executeWalletBankTransaction(WalletTransactionRequest request, boolean mainToBank,
                                                               long maximumBalanceMinor) {
            throw new AssertionError("The tested rollback is not a wallet-bank movement");
        }

        @Override public Optional<TransactionRecord> findTransaction(UUID transactionId) {
            return original.id().equals(transactionId) ? Optional.of(original) : Optional.empty();
        }
        @Override public TransactionPage findTransactions(TransactionQuery query) {
            return new TransactionPage(List.of(), query.offset(), query.limit(), false);
        }
        @Override public EconomyStatistics getEconomyStatistics() {
            return new EconomyStatistics(0L, 0L, null, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        }
        @Override public List<TransactionRecord> getAllWalletTransactions() { return List.of(original); }
    }
}
