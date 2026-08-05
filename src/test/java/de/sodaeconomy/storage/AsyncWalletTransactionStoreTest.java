package de.sodaeconomy.storage;

import de.sodaeconomy.SodaEconomy;
import de.sodaeconomy.support.InMemoryStorage;
import de.sodaeconomy.support.MockBukkitTestBase;
import de.sodaeconomy.transaction.EconomyStatistics;
import de.sodaeconomy.transaction.TransactionOrigin;
import de.sodaeconomy.transaction.TransactionFailureReason;
import de.sodaeconomy.transaction.TransactionPage;
import de.sodaeconomy.transaction.TransactionQuery;
import de.sodaeconomy.transaction.TransactionRecord;
import de.sodaeconomy.transaction.TransactionRecords;
import de.sodaeconomy.transaction.TransactionResult;
import de.sodaeconomy.transaction.TransactionService;
import de.sodaeconomy.transaction.TransactionType;
import de.sodaeconomy.transaction.WalletAccountState;
import de.sodaeconomy.transaction.WalletTransactionRequest;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncWalletTransactionStoreTest extends MockBukkitTestBase {
    private ControllableStorage delegate;
    private AsyncWalletTransactionStore asynchronousStore;
    private TransactionService transactions;

    @AfterEach
    void closeServices() {
        if (transactions != null) {
            transactions.close();
        }
    }

    @Test
    void updatesBalancesImmediatelyBeforeTheBlockedBackendWriteCompletes() throws Exception {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        initialize(source, 100D, target, 0D);
        delegate.blockNextWalletTransaction();

        TransactionResult result = transactions.transferSynchronously(source, target, 25D,
                TransactionType.PLAYER_TRANSFER, TransactionOrigin.console(), "Asynchronous transfer", Map.of());

        assertTrue(result.isSuccessful());
        assertTrue(delegate.awaitBlockedWrite());
        assertEquals(75D, transactions.getBalanceSynchronously(source));
        assertEquals(25D, transactions.getBalanceSynchronously(target));
        assertEquals(100D, delegate.getBalance(source));
        assertEquals(0D, delegate.getBalance(target));

        delegate.releaseBlockedWrite();

        assertTrue(asynchronousStore.awaitPersistence(Duration.ofSeconds(2)));
        assertEquals(75D, delegate.getBalance(source));
        assertEquals(25D, delegate.getBalance(target));
    }

    @Test
    void commitsQueuedWalletTransactionsInAcceptanceOrder() throws Exception {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        initialize(source, 100D, target, 0D);
        delegate.blockNextWalletTransaction();

        TransactionResult transfer = transactions.transferSynchronously(source, target, 20D,
                TransactionType.PLAYER_TRANSFER, TransactionOrigin.console(), "First", Map.of());
        assertTrue(delegate.awaitBlockedWrite());
        TransactionResult deposit = transactions.depositSynchronously(target, 5D, TransactionType.ADMIN_GIVE,
                TransactionOrigin.console(), "Second", Map.of());

        delegate.releaseBlockedWrite();

        assertTrue(asynchronousStore.awaitPersistence(Duration.ofSeconds(2)));
        assertEquals(List.of(transfer.transaction().id(), deposit.transaction().id()), delegate.executedTransactionIds());
        assertEquals(80D, delegate.getBalance(source));
        assertEquals(25D, delegate.getBalance(target));
    }

    @Test
    void retriesAFailedWriteWithoutLosingTheImmediateBalanceChange() throws Exception {
        UUID playerId = UUID.randomUUID();
        initialize(playerId, 10D);
        delegate.failWalletTransactions(1);

        TransactionResult result = transactions.depositSynchronously(playerId, 5D, TransactionType.ADMIN_GIVE,
                TransactionOrigin.console(), "Retry", Map.of());

        assertTrue(result.isSuccessful());
        assertEquals(15D, transactions.getBalanceSynchronously(playerId));
        assertTrue(asynchronousStore.awaitPersistence(Duration.ofSeconds(2)));
        assertEquals(2, delegate.walletTransactionAttempts());
        assertEquals(15D, delegate.getBalance(playerId));
        assertEquals(result.transaction(), delegate.findTransaction(result.transaction().id()).orElseThrow());
    }

    @Test
    void publicApiCompletionWaitsForTheDurableQueuedWrite() throws Exception {
        UUID playerId = UUID.randomUUID();
        initialize(playerId, 10D);
        delegate.blockNextWalletTransaction();

        CompletableFuture<TransactionResult> result = transactions.deposit(playerId, 5D, TransactionType.API_DEPOSIT,
                TransactionOrigin.api("AsyncWalletTransactionStoreTest"), "Durable API result", Map.of());

        assertTrue(delegate.awaitBlockedWrite());
        assertEquals(15D, transactions.getBalanceSynchronously(playerId));
        assertFalse(result.isDone());

        delegate.releaseBlockedWrite();

        assertTrue(result.get(2L, TimeUnit.SECONDS).isSuccessful());
        assertEquals(15D, delegate.getBalance(playerId));
    }

    @Test
    void shutdownWaitsForQueuedWritesBeforeTheUnderlyingStorageCanBeReleased() throws Exception {
        UUID playerId = UUID.randomUUID();
        initialize(playerId, 10D);
        delegate.blockNextWalletTransaction();

        assertTrue(transactions.depositSynchronously(playerId, 5D, TransactionType.ADMIN_GIVE,
                TransactionOrigin.console(), "Shutdown", Map.of()).isSuccessful());
        assertTrue(delegate.awaitBlockedWrite());

        // Use a lambda instead of a bare method reference so StackWalker can still see this
        // internal SodaEconomy test class when TransactionService#close checks its caller.
        CompletableFuture<Void> shutdown = CompletableFuture.runAsync(() -> transactions.close());
        Thread.sleep(50L);
        assertFalse(shutdown.isDone());

        delegate.releaseBlockedWrite();
        shutdown.get(2L, TimeUnit.SECONDS);

        assertEquals(15D, delegate.getBalance(playerId));
    }

    @Test
    void rejectsANewMutationBeforeChangingCachedBalancesWhenTheQueueIsFull() throws Exception {
        UUID playerId = UUID.randomUUID();
        initialize(playerId, 10D, new AsyncPersistenceSettings(5L, 25L, 50L,
                1, 80, 3, 1_000L, 1_000L));
        delegate.blockNextWalletTransaction();

        TransactionResult accepted = transactions.depositSynchronously(playerId, 5D, TransactionType.ADMIN_GIVE,
                TransactionOrigin.console(), "First queued write", Map.of());
        assertTrue(accepted.isSuccessful());
        assertTrue(delegate.awaitBlockedWrite());

        TransactionResult rejected = transactions.depositSynchronously(playerId, 5D, TransactionType.ADMIN_GIVE,
                TransactionOrigin.console(), "Queue full", Map.of());

        assertFalse(rejected.isSuccessful());
        assertEquals(TransactionFailureReason.STORAGE_FAILURE, rejected.failureReason());
        assertEquals(15D, transactions.getBalanceSynchronously(playerId));
        assertEquals(1, asynchronousStore.pendingWriteCount());

        delegate.releaseBlockedWrite();
        assertTrue(asynchronousStore.awaitPersistence(Duration.ofSeconds(2)));
    }

    @Test
    void pausesAfterBoundedFailuresAndRejectsFurtherMutationsWithoutChangingTheCache() throws Exception {
        UUID playerId = UUID.randomUUID();
        initialize(playerId, 10D, new AsyncPersistenceSettings(1L, 1L, 25L,
                10, 80, 2, 10_000L, 1_000L));
        delegate.failWalletTransactions(2);

        TransactionResult accepted = transactions.depositSynchronously(playerId, 5D, TransactionType.ADMIN_GIVE,
                TransactionOrigin.console(), "Will pause", Map.of());
        assertTrue(accepted.isSuccessful());
        assertTrue(awaitHealth(PersistenceHealth.PAUSED, Duration.ofSeconds(2)));

        TransactionResult rejected = transactions.depositSynchronously(playerId, 5D, TransactionType.ADMIN_GIVE,
                TransactionOrigin.console(), "Rejected while paused", Map.of());

        assertFalse(rejected.isSuccessful());
        assertEquals(TransactionFailureReason.STORAGE_FAILURE, rejected.failureReason());
        assertEquals(15D, transactions.getBalanceSynchronously(playerId));
        assertEquals(2, delegate.walletTransactionAttempts());
    }

    @Test
    void pausesAndCompletesTheDurabilityFutureExceptionallyWhenTheDelegateReturnsAnotherOutcome() throws Exception {
        UUID playerId = UUID.randomUUID();
        initialize(playerId, 10D, new AsyncPersistenceSettings(1L, 1L, 25L,
                10, 80, 2, 10_000L, 100L));
        delegate.returnMismatchedTransactionOutcome();

        TransactionResult accepted = transactions.depositSynchronously(playerId, 5D, TransactionType.ADMIN_GIVE,
                TransactionOrigin.console(), "Mismatched outcome", Map.of());
        assertTrue(accepted.isSuccessful());

        assertThrows(ExecutionException.class, () -> asynchronousStore
                .awaitTransactionPersistence(accepted.transaction().id()).get(2L, TimeUnit.SECONDS));
        assertEquals(PersistenceHealth.PAUSED, asynchronousStore.getPersistenceStatus().health());
        assertEquals(1, asynchronousStore.pendingWriteCount());
    }

    @Test
    void acceptsEquivalentFailedPersistenceOutcomeWithDifferentStorageDetail() throws Exception {
        UUID playerId = UUID.randomUUID();
        initialize(playerId, 10D, 100D);
        delegate.returnEquivalentFailureWithDifferentDetail();

        TransactionResult rejected = transactions.setBalanceSynchronously(playerId, 200D, TransactionType.ADMIN_SET,
                TransactionOrigin.console(), "Too high", Map.of());

        assertFalse(rejected.isSuccessful());
        assertEquals(TransactionFailureReason.MAXIMUM_BALANCE_EXCEEDED, rejected.failureReason());
        assertTrue(asynchronousStore.awaitPersistence(Duration.ofSeconds(2)));
        assertEquals(PersistenceHealth.HEALTHY, asynchronousStore.getPersistenceStatus().health());
        assertEquals(0, asynchronousStore.pendingWriteCount());
        assertEquals(10D, delegate.getBalance(playerId));
    }

    @Test
    void nonRetryablePersistenceFailureCompletesThePublicApiFutureExceptionally() throws Exception {
        UUID playerId = UUID.randomUUID();
        initialize(playerId, 10D, new AsyncPersistenceSettings(1L, 1L, 25L,
                10, 80, 2, 10_000L, 100L));
        delegate.failNextWalletTransactionPermanently();

        CompletableFuture<TransactionResult> result = transactions.deposit(playerId, 5D,
                TransactionType.API_DEPOSIT, TransactionOrigin.api("AsyncWalletTransactionStoreTest"),
                "Permanent failure", Map.of());

        assertThrows(ExecutionException.class, () -> result.get(2L, TimeUnit.SECONDS));
        assertEquals(PersistenceHealth.PAUSED, asynchronousStore.getPersistenceStatus().health());
        assertEquals(1, asynchronousStore.pendingWriteCount());
    }

    @Test
    void loadsVeryLargeExactMinorUnitBalancesWithoutADoubleRoundTrip() throws Exception {
        UUID playerId = UUID.randomUUID();
        long exactBalance = 9_007_199_254_740_993L;
        delegate = new ControllableStorage();
        delegate.setExactWalletBalance(playerId, exactBalance);

        asynchronousStore = new AsyncWalletTransactionStore(plugin, delegate, delegate,
                new AsyncPersistenceSettings(5L, 25L, 50L));
        transactions = new TransactionService(plugin, asynchronousStore, 0D, 0D);

        assertEquals(exactBalance, asynchronousStore.getAllBalanceMinorUnits().get(playerId));
    }

    @Test
    void shutdownTimeoutFailsOutstandingPublicApiFutures() throws Exception {
        UUID playerId = UUID.randomUUID();
        initialize(playerId, 10D, new AsyncPersistenceSettings(5L, 25L, 5L,
                10, 80, 3, 50L, 10L));
        delegate.blockNextWalletTransaction();

        CompletableFuture<TransactionResult> result = transactions.deposit(playerId, 5D,
                TransactionType.API_DEPOSIT, TransactionOrigin.api("AsyncWalletTransactionStoreTest"),
                "Shutdown timeout", Map.of());
        assertTrue(delegate.awaitBlockedWrite());

        transactions.close();

        assertThrows(ExecutionException.class, () -> result.get(2L, TimeUnit.SECONDS));
        assertEquals(PersistenceHealth.STOPPED, asynchronousStore.getPersistenceStatus().health());
        delegate.releaseBlockedWrite();
    }


    @Test
    void recoverySnapshotRestoresAcceptedWriteAfterHardCrash() throws Exception {
        UUID playerId = UUID.randomUUID();
        AsyncPersistenceSettings settings = new AsyncPersistenceSettings(5L, 25L, 50L);
        initialize(playerId, 10D, settings);
        delegate.blockNextWalletTransaction();

        TransactionResult result = transactions.depositSynchronously(playerId, 5D, TransactionType.ADMIN_GIVE,
                TransactionOrigin.console(), "Recovered after crash", Map.of());

        assertTrue(result.isSuccessful());
        assertTrue(delegate.awaitBlockedWrite());
        assertEquals(15D, transactions.getBalanceSynchronously(playerId));
        assertEquals(10D, delegate.getBalance(playerId));
        assertTrue(new java.io.File(plugin.getDataFolder(), "local-persistence-recovery.yml").isFile());

        ControllableStorage restoredBackend = new ControllableStorage();
        restoredBackend.setBalance(playerId, 10D);
        AsyncWalletTransactionStore restoredStore = new AsyncWalletTransactionStore(plugin, restoredBackend,
                restoredBackend, settings);
        try {
            assertEquals(15D, restoredStore.getBalance(playerId));
            assertEquals(result.transaction(), restoredBackend.findTransaction(result.transaction().id()).orElseThrow());
            assertFalse(new java.io.File(plugin.getDataFolder(), "local-persistence-recovery.yml").exists());
        } finally {
            restoredStore.close();
            delegate.releaseBlockedWrite();
            asynchronousStore.close();
        }
    }

    @Test
    void readsPersistedHistoryPromptlyWhileAnEarlierWriteIsBlocked() throws Exception {
        UUID playerId = UUID.randomUUID();
        initialize(playerId, 10D);
        delegate.blockNextWalletTransaction();

        assertTrue(transactions.depositSynchronously(playerId, 5D, TransactionType.ADMIN_GIVE,
                TransactionOrigin.console(), "Blocked write", Map.of()).isSuccessful());
        assertTrue(delegate.awaitBlockedWrite());

        assertEquals(0, asynchronousStore.findTransactions(TransactionQuery.recent()).records().size());

        delegate.releaseBlockedWrite();
        assertTrue(asynchronousStore.awaitPersistence(Duration.ofSeconds(2)));
    }

    private void initialize(UUID firstPlayer, double firstBalance) throws Exception {
        initialize(firstPlayer, firstBalance, null, 0D);
    }

    private void initialize(UUID firstPlayer, double firstBalance, AsyncPersistenceSettings settings) throws Exception {
        initialize(firstPlayer, firstBalance, null, 0D, settings);
    }

    private void initialize(UUID firstPlayer, double firstBalance, double maximumBalance) throws Exception {
        initialize(firstPlayer, firstBalance, null, 0D, new AsyncPersistenceSettings(5L, 25L, 50L), maximumBalance);
    }

    private void initialize(UUID firstPlayer, double firstBalance, UUID secondPlayer, double secondBalance) throws Exception {
        initialize(firstPlayer, firstBalance, secondPlayer, secondBalance,
                new AsyncPersistenceSettings(5L, 25L, 50L));
    }

    private void initialize(UUID firstPlayer, double firstBalance, UUID secondPlayer, double secondBalance,
                            AsyncPersistenceSettings settings) throws Exception {
        initialize(firstPlayer, firstBalance, secondPlayer, secondBalance, settings, 0D);
    }

    private void initialize(UUID firstPlayer, double firstBalance, UUID secondPlayer, double secondBalance,
                            AsyncPersistenceSettings settings, double maximumBalance) throws Exception {
        delegate = new ControllableStorage();
        delegate.setBalance(firstPlayer, firstBalance);
        if (secondPlayer != null) {
            delegate.setBalance(secondPlayer, secondBalance);
        }
        asynchronousStore = new AsyncWalletTransactionStore(plugin, delegate, delegate, settings);
        transactions = new TransactionService(plugin, asynchronousStore, 0D, maximumBalance);
    }

    private boolean awaitHealth(PersistenceHealth expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (asynchronousStore.getPersistenceStatus().health() == expected) {
                return true;
            }
            Thread.sleep(5L);
        }
        return asynchronousStore.getPersistenceStatus().health() == expected;
    }

    /** Test double that exposes queue timing without changing the production storage contracts. */
    private static final class ControllableStorage implements Storage, WalletTransactionStore {
        private final InMemoryStorage delegate = new InMemoryStorage();
        private final AtomicInteger failedWalletTransactions = new AtomicInteger();
        private final AtomicInteger walletTransactionAttempts = new AtomicInteger();
        private final List<UUID> executedTransactionIds = new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile boolean returnMismatchedTransactionOutcome;
        private volatile boolean returnEquivalentFailureWithDifferentDetail;
        private volatile boolean permanentFailure;
        private final Map<UUID, Long> exactWalletBalances = new java.util.concurrent.ConcurrentHashMap<>();
        private volatile CountDownLatch writeEntered = new CountDownLatch(0);
        private volatile CountDownLatch writeRelease = new CountDownLatch(0);

        void blockNextWalletTransaction() {
            writeEntered = new CountDownLatch(1);
            writeRelease = new CountDownLatch(1);
        }

        boolean awaitBlockedWrite() throws InterruptedException {
            return writeEntered.await(2L, TimeUnit.SECONDS);
        }

        void releaseBlockedWrite() {
            writeRelease.countDown();
        }

        void failWalletTransactions(int count) {
            failedWalletTransactions.set(count);
        }

        void returnMismatchedTransactionOutcome() {
            returnMismatchedTransactionOutcome = true;
        }

        void returnEquivalentFailureWithDifferentDetail() {
            returnEquivalentFailureWithDifferentDetail = true;
        }

        void failNextWalletTransactionPermanently() {
            permanentFailure = true;
        }

        void setExactWalletBalance(UUID playerId, long amountMinor) {
            exactWalletBalances.put(playerId, amountMinor);
        }

        int walletTransactionAttempts() {
            return walletTransactionAttempts.get();
        }

        List<UUID> executedTransactionIds() {
            return List.copyOf(executedTransactionIds);
        }

        @Override public void init(JavaPlugin plugin) throws Exception { delegate.init(plugin); }
        @Override public Double getBalance(UUID uuid) throws Exception { return delegate.getBalance(uuid); }
        @Override public double getOrCreateBalance(UUID uuid, double initialBalance) throws Exception { return delegate.getOrCreateBalance(uuid, initialBalance); }
        @Override public void setBalance(UUID uuid, double amount) throws Exception { delegate.setBalance(uuid, amount); }
        @Override public Map<UUID, Double> getAllBalances() throws Exception { return delegate.getAllBalances(); }
        @Override public Map<UUID, Long> getAllBalanceMinorUnits() throws Exception {
            return exactWalletBalances.isEmpty() ? delegate.getAllBalanceMinorUnits() : Map.copyOf(exactWalletBalances);
        }
        @Override public void saveAll(Map<UUID, Double> balances) throws Exception { delegate.saveAll(balances); }
        @Override public Double getBankBalance(UUID uuid) throws Exception { return delegate.getBankBalance(uuid); }
        @Override public void setBankBalance(UUID uuid, double amount) throws Exception { delegate.setBankBalance(uuid, amount); }
        @Override public Map<UUID, Double> getAllBankBalances() throws Exception { return delegate.getAllBankBalances(); }
        @Override public void saveAllBank(Map<UUID, Double> bankBalances) throws Exception { delegate.saveAllBank(bankBalances); }
        @Override public void replaceSnapshot(StorageSnapshot snapshot) throws Exception { delegate.replaceSnapshot(snapshot); }
        @Override public boolean transferMain(UUID source, UUID target, double amount) throws Exception { return delegate.transferMain(source, target, amount); }
        @Override public boolean transferMainAndBank(UUID uuid, boolean mainToBank, double amount) throws Exception { return delegate.transferMainAndBank(uuid, mainToBank, amount); }
        @Override public void close() { delegate.close(); }
        @Override public boolean isDebug() { return delegate.isDebug(); }
        @Override public WalletAccountState ensureWalletAccount(UUID playerId, long startingBalanceMinor, Instant timestamp) throws Exception { return delegate.ensureWalletAccount(playerId, startingBalanceMinor, timestamp); }

        @Override
        public TransactionRecord executeWalletTransaction(WalletTransactionRequest request, long maximumBalanceMinor)
                throws Exception {
            walletTransactionAttempts.incrementAndGet();
            writeEntered.countDown();
            if (!writeRelease.await(2L, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The test write was not released");
            }
            if (permanentFailure) {
                permanentFailure = false;
                throw new IllegalArgumentException("Simulated permanent storage rejection");
            }
            if (failedWalletTransactions.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw new IllegalStateException("Simulated transient storage failure");
            }
            TransactionRecord record = delegate.executeWalletTransaction(request, maximumBalanceMinor);
            executedTransactionIds.add(record.id());
            if (returnMismatchedTransactionOutcome) {
                returnMismatchedTransactionOutcome = false;
                return TransactionRecords.failed(request, TransactionFailureReason.INSUFFICIENT_FUNDS,
                        "Simulated authoritative rejection", null, null);
            }
            if (returnEquivalentFailureWithDifferentDetail && !record.isSuccessful()) {
                returnEquivalentFailureWithDifferentDetail = false;
                return new TransactionRecord(record.id(), record.timestamp(), record.type(), record.status(),
                        record.origin(), record.operation(), record.sourcePlayerId(), record.targetPlayerId(),
                        record.requestedAmountMinor(), record.appliedAmountMinor(), record.sourceBalanceBeforeMinor(),
                        record.sourceBalanceAfterMinor(), record.targetBalanceBeforeMinor(),
                        record.targetBalanceAfterMinor(), record.reason(), record.metadata(),
                        record.reversalOfTransactionId(), record.batchId(), record.idempotencyKey(),
                        record.failureReason(), "Storage-specific failure wording");
            }
            return record;
        }

        @Override
        public TransactionRecord executeWalletBankTransaction(WalletTransactionRequest request, boolean mainToBank,
                                                               long maximumBalanceMinor) throws Exception {
            return delegate.executeWalletBankTransaction(request, mainToBank, maximumBalanceMinor);
        }

        @Override public Optional<TransactionRecord> findTransaction(UUID transactionId) throws Exception { return delegate.findTransaction(transactionId); }
        @Override public TransactionPage findTransactions(TransactionQuery query) throws Exception { return delegate.findTransactions(query); }
        @Override public EconomyStatistics getEconomyStatistics() throws Exception { return delegate.getEconomyStatistics(); }
        @Override public List<TransactionRecord> getAllWalletTransactions() throws Exception { return delegate.getAllWalletTransactions(); }
    }
}
