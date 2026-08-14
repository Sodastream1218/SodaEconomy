package de.sodaeconomy.storage;

import de.sodaeconomy.Money;
import de.sodaeconomy.SodaEconomy;
import de.sodaeconomy.identity.PlayerIdentity;
import de.sodaeconomy.transaction.EconomyStatistics;
import de.sodaeconomy.transaction.EconomyAnalytics;
import de.sodaeconomy.transaction.PlayerTransactionStatistics;
import de.sodaeconomy.transaction.TransactionFailureReason;
import de.sodaeconomy.transaction.TransactionPage;
import de.sodaeconomy.transaction.TransactionQuery;
import de.sodaeconomy.transaction.TransactionRecord;
import de.sodaeconomy.transaction.TransactionRecords;
import de.sodaeconomy.transaction.WalletAccountState;
import de.sodaeconomy.transaction.WalletOperation;
import de.sodaeconomy.transaction.WalletTransactionRequest;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

/**
 * Write-behind decorator for the existing atomic wallet transaction stores.
 *
 * <p>The cache is the runtime source of truth for single-server YAML and SQLite installations:
 * callers receive a fully validated transaction result and immediately observe the resulting
 * wallet or bank balance. The exact same immutable request is then committed by one ordered
 * worker. MySQL deliberately does not use this optimistic decorator; it is persisted through the
 * database-authoritative path so multiple server instances cannot diverge.</p>
 *
 * <p>A failed write remains at the head of the queue and is retried with capped exponential
 * backoff. Later writes never overtake it, so a temporary storage outage cannot reorder balance
 * changes. The queue is bounded, exposes an explicit health state, and stops accepting further
 * local mutations while recovery is paused. During a controlled plugin shutdown it drains only up
 * to its configured deadline before releasing the underlying storage.</p>
 */
@SuppressWarnings("removal")
public final class AsyncWalletTransactionStore implements Storage, WalletTransactionStore {
    private final SodaEconomy plugin;
    private final Storage storage;
    private final WalletTransactionStore transactionStore;
    private final AsyncPersistenceSettings settings;
    private final LocalPersistenceRecoveryStore recoveryStore;
    private final ReentrantLock stateLock = new ReentrantLock(true);
    private final Condition queueDrained = stateLock.newCondition();
    private final Map<UUID, Long> walletBalances = new HashMap<>();
    private final Map<UUID, Long> bankBalances = new HashMap<>();
    private final Map<UUID, TransactionRecord> pendingTransactions = new HashMap<>();
    private final Map<UUID, CompletableFuture<Void>> transactionPersistence = new HashMap<>();
    private final Map<UUID, CompletableFuture<Void>> accountPersistence = new HashMap<>();
    private final Map<String, TransactionRecord> pendingIdempotencyKeys = new HashMap<>();
    private final Map<UUID, TransactionRecord> pendingRollbacks = new HashMap<>();
    private final ArrayDeque<PendingWrite> writes = new ArrayDeque<>();
    private final ScheduledThreadPoolExecutor worker;
    private final AtomicBoolean acceptingWrites = new AtomicBoolean(true);
    private ScheduledFuture<?> scheduledDrain;
    private PersistenceHealth health = PersistenceHealth.HEALTHY;
    private int consecutiveFailures;
    private Instant lastSuccessfulWriteAt;
    private Instant lastFailureAt;
    private String lastFailureSummary;
    private Instant nextAttemptAt;
    private boolean queueWarningLogged;

    public AsyncWalletTransactionStore(SodaEconomy plugin, Storage storage, WalletTransactionStore transactionStore,
                                       AsyncPersistenceSettings settings) throws Exception {
        this(plugin, storage, transactionStore, settings, new LocalPersistenceRecoveryStore(plugin));
    }

    AsyncWalletTransactionStore(SodaEconomy plugin, Storage storage, WalletTransactionStore transactionStore,
                                AsyncPersistenceSettings settings, LocalPersistenceRecoveryStore recoveryStore)
            throws Exception {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.transactionStore = Objects.requireNonNull(transactionStore, "transactionStore");
        this.settings = Objects.requireNonNull(settings, "settings");
        if (storage != transactionStore) {
            throw new IllegalArgumentException("The wallet transaction store must be the active storage instance");
        }
        this.recoveryStore = Objects.requireNonNull(recoveryStore, "recoveryStore");
        worker = new ScheduledThreadPoolExecutor(1, new PersistenceThreadFactory());
        worker.setRemoveOnCancelPolicy(true);
        this.recoveryStore.restoreIfPresent(storage);
        reloadCache();
    }

    /** Returns the number of writes which have been accepted but not durably committed yet. */
    public int pendingWriteCount() {
        stateLock.lock();
        try {
            return writes.size();
        } finally {
            stateLock.unlock();
        }
    }

    /** Returns an immutable diagnostic snapshot without exposing mutable queue state. */
    public PersistenceStatus getPersistenceStatus() {
        stateLock.lock();
        try {
            PendingWrite head = writes.peekFirst();
            return new PersistenceStatus(health, writes.size(), settings.queueCapacity(), consecutiveFailures,
                    head == null ? null : head.enqueuedAt(), lastSuccessfulWriteAt, lastFailureAt,
                    lastFailureSummary, nextAttemptAt);
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Completes once the supplied accepted transaction has reached the underlying atomic store.
     * A transaction that was already persisted, or did not produce a durable record, completes
     * immediately.
     */
    public CompletableFuture<Void> awaitTransactionPersistence(UUID transactionId) {
        if (transactionId == null) {
            return CompletableFuture.completedFuture(null);
        }
        stateLock.lock();
        try {
            return transactionPersistence.getOrDefault(transactionId, CompletableFuture.completedFuture(null));
        } finally {
            stateLock.unlock();
        }
    }

    /** Completes once a queued initial account write has reached the underlying storage. */
    public CompletableFuture<Void> awaitAccountPersistence(UUID playerId) {
        if (playerId == null) return CompletableFuture.completedFuture(null);
        stateLock.lock();
        try {
            return accountPersistence.getOrDefault(playerId, CompletableFuture.completedFuture(null));
        } finally {
            stateLock.unlock();
        }
    }

    /** Waits for queued writes to commit without interacting with Bukkit or Paper APIs. */
    public boolean awaitPersistence(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        long remainingNanos = timeout.toNanos();
        stateLock.lock();
        try {
            while (!writes.isEmpty()) {
                if (health == PersistenceHealth.PAUSED || health == PersistenceHealth.STOPPED) {
                    return false;
                }
                if (remainingNanos <= 0L) {
                    return false;
                }
                remainingNanos = queueDrained.awaitNanos(remainingNanos);
            }
            return true;
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public void init(JavaPlugin plugin) throws Exception {
        if (plugin != this.plugin) {
            throw new IllegalArgumentException("The asynchronous storage decorator belongs to a different plugin");
        }
        storage.init(plugin);
        reloadCache();
    }

    @Override
    public Double getBalance(UUID uuid) {
        Long amount = getBalanceMinorUnits(uuid);
        return amount == null ? null : Money.fromMinorUnits(amount);
    }

    @Override
    public Long getBalanceMinorUnits(UUID uuid) {
        StorageAccessGuard.requireInternalCaller("AsyncWalletTransactionStore.getBalanceMinorUnits",
                AsyncWalletTransactionStore.class);
        if (uuid == null) return null;
        stateLock.lock();
        try {
            return walletBalances.get(uuid);
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public double getOrCreateBalance(UUID uuid, double initialBalance) throws Exception {
        StorageAccessGuard.requireInternalCaller("AsyncWalletTransactionStore.getOrCreateBalance", AsyncWalletTransactionStore.class);
        if (uuid == null || !Money.isValid(initialBalance) || initialBalance < 0D) {
            throw new IllegalArgumentException("The player ID and initial balance must be valid");
        }
        return Money.fromMinorUnits(ensureWalletAccount(uuid, Money.toMinorUnits(initialBalance), Instant.now())
                .balanceMinor());
    }

    @Override
    public void setBalance(UUID uuid, double amount) throws Exception {
        StorageAccessGuard.requireInternalCaller("AsyncWalletTransactionStore.setBalance", AsyncWalletTransactionStore.class);
        requireMutable();
        if (uuid == null || !Money.isValid(amount) || amount < 0D) {
            throw new IllegalArgumentException("The player ID and wallet balance must be valid");
        }
        long minor = Money.toMinorUnits(amount);
        stateLock.lock();
        try {
            requireWritableCapacityLocked();
            BalanceBackup backup = BalanceBackup.capture(walletBalances, Set.of(uuid));
            walletBalances.put(uuid, minor);
            try {
                enqueueLocked("legacy wallet balance update for " + uuid, () -> {
                    storage.setBalance(uuid, Money.fromMinorUnits(minor));
                    return null;
                }, RecoveryDelta.wallet(Map.of(uuid, minor)));
            } catch (Exception exception) {
                backup.restore(walletBalances);
                throw exception;
            }
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public Map<UUID, Double> getAllBalances() {
        stateLock.lock();
        try {
            return majorUnitCopy(walletBalances);
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public Map<UUID, Long> getAllBalanceMinorUnits() {
        StorageAccessGuard.requireInternalCaller("AsyncWalletTransactionStore.getAllBalanceMinorUnits", AsyncWalletTransactionStore.class);
        stateLock.lock();
        try {
            return Map.copyOf(walletBalances);
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public void saveAll(Map<UUID, Double> balances) throws Exception {
        StorageAccessGuard.requireInternalCaller("AsyncWalletTransactionStore.saveAll", AsyncWalletTransactionStore.class);
        requireMutable();
        Map<UUID, Long> values = toMinorUnitMap(balances, "wallet balances");
        Map<UUID, Double> persistedValues = majorUnitCopy(values);
        stateLock.lock();
        try {
            requireWritableCapacityLocked();
            BalanceBackup backup = BalanceBackup.capture(walletBalances, values.keySet());
            walletBalances.putAll(values);
            try {
                enqueueLocked("legacy wallet balance batch", () -> {
                    storage.saveAll(persistedValues);
                    return null;
                }, RecoveryDelta.wallet(values));
            } catch (Exception exception) {
                backup.restore(walletBalances);
                throw exception;
            }
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public Map<UUID, PlayerIdentity> getAllPlayerIdentities() throws Exception {
        StorageAccessGuard.requireInternalCaller("AsyncWalletTransactionStore.getAllPlayerIdentities", AsyncWalletTransactionStore.class);
        return storage.getAllPlayerIdentities();
    }

    @Override
    public Optional<PlayerIdentity> findPlayerIdentity(UUID playerId) throws Exception {
        StorageAccessGuard.requireInternalCaller("AsyncWalletTransactionStore.findPlayerIdentity", AsyncWalletTransactionStore.class);
        return storage.findPlayerIdentity(playerId);
    }

    @Override
    public List<PlayerIdentity> findPlayerIdentitiesByNormalizedName(String normalizedName) throws Exception {
        StorageAccessGuard.requireInternalCaller("AsyncWalletTransactionStore.findPlayerIdentitiesByNormalizedName", AsyncWalletTransactionStore.class);
        return storage.findPlayerIdentitiesByNormalizedName(normalizedName);
    }

    @Override
    public Map<UUID, PlayerIdentity> findPlayerIdentities(Set<UUID> playerIds) throws Exception {
        StorageAccessGuard.requireInternalCaller("AsyncWalletTransactionStore.findPlayerIdentities", AsyncWalletTransactionStore.class);
        return storage.findPlayerIdentities(playerIds);
    }

    @Override
    public void upsertPlayerIdentity(PlayerIdentity identity) throws Exception {
        StorageAccessGuard.requireInternalCaller("AsyncWalletTransactionStore.upsertPlayerIdentity",
                AsyncWalletTransactionStore.class);
        storage.upsertPlayerIdentity(identity);
    }

    @Override
    public void replacePlayerIdentities(Map<UUID, PlayerIdentity> identities) throws Exception {
        StorageAccessGuard.requireInternalCaller("AsyncWalletTransactionStore.replacePlayerIdentities",
                AsyncWalletTransactionStore.class);
        awaitQueueBeforeDirectOperation();
        storage.replacePlayerIdentities(identities);
    }

    @Override
    public Double getBankBalance(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        stateLock.lock();
        try {
            if (!walletBalances.containsKey(uuid) && !bankBalances.containsKey(uuid)) {
                return null;
            }
            return Money.fromMinorUnits(bankBalances.getOrDefault(uuid, 0L));
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public void setBankBalance(UUID uuid, double amount) throws Exception {
        StorageAccessGuard.requireInternalCaller("AsyncWalletTransactionStore.setBankBalance", AsyncWalletTransactionStore.class);
        requireMutable();
        if (uuid == null || !Money.isValid(amount) || amount < 0D) {
            throw new IllegalArgumentException("The player ID and bank balance must be valid");
        }
        long minor = Money.toMinorUnits(amount);
        stateLock.lock();
        try {
            requireWritableCapacityLocked();
            BalanceBackup backup = BalanceBackup.capture(bankBalances, Set.of(uuid));
            bankBalances.put(uuid, minor);
            try {
                enqueueLocked("bank balance update for " + uuid, () -> {
                    storage.setBankBalance(uuid, Money.fromMinorUnits(minor));
                    return null;
                }, RecoveryDelta.bank(Map.of(uuid, minor)));
            } catch (Exception exception) {
                backup.restore(bankBalances);
                throw exception;
            }
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public Map<UUID, Double> getAllBankBalances() {
        stateLock.lock();
        try {
            return majorUnitCopy(bankBalances);
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public Map<UUID, Long> getAllBankBalanceMinorUnits() {
        StorageAccessGuard.requireInternalCaller("AsyncWalletTransactionStore.getAllBankBalanceMinorUnits", AsyncWalletTransactionStore.class);
        stateLock.lock();
        try {
            return Map.copyOf(bankBalances);
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public void saveAllBank(Map<UUID, Double> balances) throws Exception {
        StorageAccessGuard.requireInternalCaller("AsyncWalletTransactionStore.saveAllBank", AsyncWalletTransactionStore.class);
        requireMutable();
        Map<UUID, Long> values = toMinorUnitMap(balances, "bank balances");
        Map<UUID, Double> persistedValues = majorUnitCopy(values);
        stateLock.lock();
        try {
            requireWritableCapacityLocked();
            BalanceBackup backup = BalanceBackup.capture(bankBalances, values.keySet());
            bankBalances.putAll(values);
            try {
                enqueueLocked("bank balance batch", () -> {
                    storage.saveAllBank(persistedValues);
                    return null;
                }, RecoveryDelta.bank(values));
            } catch (Exception exception) {
                backup.restore(bankBalances);
                throw exception;
            }
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public void saveAllBankMinorUnits(Map<UUID, Long> balances) throws Exception {
        StorageAccessGuard.requireInternalCaller("AsyncWalletTransactionStore.saveAllBankMinorUnits", AsyncWalletTransactionStore.class);
        requireMutable();
        Map<UUID, Long> values = validatedMinorUnitMap(balances, "bank balances");
        stateLock.lock();
        try {
            requireWritableCapacityLocked();
            BalanceBackup backup = BalanceBackup.capture(bankBalances, values.keySet());
            bankBalances.putAll(values);
            try {
                enqueueLocked("exact bank balance batch", () -> {
                    storage.saveAllBankMinorUnits(values);
                    return null;
                }, RecoveryDelta.bank(values));
            } catch (Exception exception) {
                backup.restore(bankBalances);
                throw exception;
            }
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public void replaceSnapshot(StorageSnapshot snapshot) throws Exception {
        StorageAccessGuard.requireInternalCaller("AsyncWalletTransactionStore.replaceSnapshot", AsyncWalletTransactionStore.class);
        Objects.requireNonNull(snapshot, "snapshot");
        awaitQueueBeforeDirectOperation();
        storage.replaceSnapshot(snapshot);
        reloadCache();
    }

    @Override
    public void replaceMinorUnitSnapshot(Map<UUID, Long> balances, Map<UUID, Long> bankBalances,
                                         List<TransactionRecord> walletTransactions) throws Exception {
        replaceMinorUnitSnapshot(balances, bankBalances, walletTransactions, getAllPlayerIdentities());
    }

    @Override
    public void replaceMinorUnitSnapshot(Map<UUID, Long> balances, Map<UUID, Long> bankBalances,
                                         List<TransactionRecord> walletTransactions,
                                         Map<UUID, PlayerIdentity> playerIdentities) throws Exception {
        StorageAccessGuard.requireInternalCaller("AsyncWalletTransactionStore.replaceMinorUnitSnapshot",
                AsyncWalletTransactionStore.class);
        Map<UUID, Long> exactWallets = validatedMinorUnitMap(balances, "wallet balances");
        Map<UUID, Long> exactBanks = validatedMinorUnitMap(bankBalances, "bank balances");
        Objects.requireNonNull(walletTransactions, "walletTransactions");
        Objects.requireNonNull(playerIdentities, "playerIdentities");
        awaitQueueBeforeDirectOperation();
        storage.replaceMinorUnitSnapshot(exactWallets, exactBanks, List.copyOf(walletTransactions),
                Map.copyOf(playerIdentities));
        reloadCache();
    }

    @Override
    public boolean transferMain(UUID source, UUID target, double amount) throws Exception {
        StorageAccessGuard.requireInternalCaller("AsyncWalletTransactionStore.transferMain", AsyncWalletTransactionStore.class);
        requireMutable();
        if (source == null || target == null || source.equals(target) || !Money.isPositive(amount)) {
            return false;
        }
        long amountMinor = Money.toMinorUnits(amount);
        stateLock.lock();
        try {
            Long sourceBefore = walletBalances.get(source);
            Long targetBefore = walletBalances.get(target);
            if (sourceBefore == null || targetBefore == null || sourceBefore < amountMinor) {
                return false;
            }
            long targetAfter;
            try {
                targetAfter = Math.addExact(targetBefore, amountMinor);
            } catch (ArithmeticException exception) {
                return false;
            }
            if (!canAcceptWriteLocked()) {
                return false;
            }
            BalanceBackup backup = BalanceBackup.capture(walletBalances, Set.of(source, target));
            walletBalances.put(source, sourceBefore - amountMinor);
            walletBalances.put(target, targetAfter);
            try {
                enqueueLocked("legacy wallet transfer " + source + " to " + target, () -> {
                    storage.transferMain(source, target, Money.fromMinorUnits(amountMinor));
                    return null;
                }, RecoveryDelta.wallet(Map.of(source, sourceBefore - amountMinor, target, targetAfter)));
                return true;
            } catch (Exception exception) {
                backup.restore(walletBalances);
                throw exception;
            }
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public boolean transferMainAndBank(UUID uuid, boolean mainToBank, double amount) throws Exception {
        StorageAccessGuard.requireInternalCaller("AsyncWalletTransactionStore.transferMainAndBank", AsyncWalletTransactionStore.class);
        requireMutable();
        if (uuid == null || !Money.isPositive(amount)) {
            return false;
        }
        long amountMinor = Money.toMinorUnits(amount);
        stateLock.lock();
        try {
            Long walletBefore = walletBalances.get(uuid);
            long bankBefore = bankBalances.getOrDefault(uuid, 0L);
            if (walletBefore == null) {
                return false;
            }
            long walletAfter;
            long bankAfter;
            if (mainToBank) {
                if (walletBefore < amountMinor) {
                    return false;
                }
                try {
                    bankAfter = Math.addExact(bankBefore, amountMinor);
                } catch (ArithmeticException exception) {
                    return false;
                }
                walletAfter = walletBefore - amountMinor;
            } else {
                if (bankBefore < amountMinor) {
                    return false;
                }
                try {
                    walletAfter = Math.addExact(walletBefore, amountMinor);
                } catch (ArithmeticException exception) {
                    return false;
                }
                bankAfter = bankBefore - amountMinor;
            }
            if (!canAcceptWriteLocked()) {
                return false;
            }
            BalanceBackup walletBackup = BalanceBackup.capture(walletBalances, Set.of(uuid));
            BalanceBackup bankBackup = BalanceBackup.capture(bankBalances, Set.of(uuid));
            walletBalances.put(uuid, walletAfter);
            bankBalances.put(uuid, bankAfter);
            try {
                enqueueLocked("legacy wallet-bank transfer for " + uuid, () -> {
                    storage.transferMainAndBank(uuid, mainToBank, Money.fromMinorUnits(amountMinor));
                    return null;
                }, RecoveryDelta.walletAndBank(Map.of(uuid, walletAfter), Map.of(uuid, bankAfter)));
                return true;
            } catch (Exception exception) {
                walletBackup.restore(walletBalances);
                bankBackup.restore(bankBalances);
                throw exception;
            }
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public WalletAccountState ensureWalletAccount(UUID playerId, long startingBalanceMinor, Instant timestamp)
            throws Exception {
        StorageAccessGuard.requireInternalCaller("AsyncWalletTransactionStore.ensureWalletAccount", AsyncWalletTransactionStore.class);
        requireMutable();
        if (playerId == null || timestamp == null || startingBalanceMinor < 0L) {
            throw new IllegalArgumentException("The wallet account parameters must be valid");
        }
        stateLock.lock();
        try {
            Long existing = walletBalances.get(playerId);
            if (existing != null) {
                return new WalletAccountState(existing, false);
            }
            requireWritableCapacityLocked();
            BalanceBackup walletBackup = BalanceBackup.capture(walletBalances, Set.of(playerId));
            BalanceBackup bankBackup = BalanceBackup.capture(bankBalances, Set.of(playerId));
            walletBalances.put(playerId, startingBalanceMinor);
            bankBalances.putIfAbsent(playerId, 0L);
            CompletableFuture<Void> completion = new CompletableFuture<>();
            accountPersistence.put(playerId, completion);
            completion.whenComplete((ignored, failure) -> {
                stateLock.lock();
                try {
                    accountPersistence.remove(playerId, completion);
                } finally {
                    stateLock.unlock();
                }
            });
            try {
                LocalRecoveryState.AccountInitialization initialization =
                        new LocalRecoveryState.AccountInitialization(startingBalanceMinor, timestamp);
                enqueueLocked("wallet account initialization for " + playerId, () -> {
                    transactionStore.ensureWalletAccount(playerId, startingBalanceMinor, timestamp);
                    return null;
                }, null, completion, RecoveryDelta.accountInitialization(playerId, startingBalanceMinor, initialization));
            } catch (Exception exception) {
                accountPersistence.remove(playerId, completion);
                walletBackup.restore(walletBalances);
                bankBackup.restore(bankBalances);
                completion.completeExceptionally(exception);
                throw exception;
            }
            return new WalletAccountState(startingBalanceMinor, true);
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Creates or verifies an account only after all older local writes have committed. This path is
     * used by synchronous compatibility adapters which must never report success before durability.
     */
    public WalletAccountState ensureWalletAccountDurably(UUID playerId, long startingBalanceMinor, Instant timestamp,
                                                          Duration admissionTimeout) throws Exception {
        StorageAccessGuard.requireInternalCaller("AsyncWalletTransactionStore.ensureWalletAccountDurably",
                AsyncWalletTransactionStore.class);
        requireMutable();
        Objects.requireNonNull(admissionTimeout, "admissionTimeout");
        if (playerId == null || timestamp == null || startingBalanceMinor < 0L || admissionTimeout.isNegative()) {
            throw new IllegalArgumentException("The durable wallet account parameters must be valid");
        }
        stateLock.lockInterruptibly();
        try {
            awaitQueueDrainForDirectWriteLocked(admissionTimeout);
            WalletAccountState result = transactionStore.ensureWalletAccount(playerId, startingBalanceMinor, timestamp);
            walletBalances.put(playerId, result.balanceMinor());
            bankBalances.putIfAbsent(playerId, 0L);
            return result;
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Commits a wallet transaction directly to the underlying local store after the ordered queue
     * has drained, then updates the runtime cache from the authoritative immutable record.
     */
    public TransactionRecord executeWalletTransactionDurably(WalletTransactionRequest request,
                                                               long maximumBalanceMinor,
                                                               Duration admissionTimeout) throws Exception {
        StorageAccessGuard.requireInternalCaller("AsyncWalletTransactionStore.executeWalletTransactionDurably",
                AsyncWalletTransactionStore.class);
        requireMutable();
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(admissionTimeout, "admissionTimeout");
        if (maximumBalanceMinor < 0L || admissionTimeout.isNegative()) {
            throw new IllegalArgumentException("The durable wallet transaction parameters must be valid");
        }
        stateLock.lockInterruptibly();
        try {
            awaitQueueDrainForDirectWriteLocked(admissionTimeout);
            TransactionRecord record = transactionStore.executeWalletTransaction(request, maximumBalanceMinor);
            applyAuthoritativeWalletRecordLocked(record);
            return record;
        } finally {
            stateLock.unlock();
        }
    }

    private void awaitQueueDrainForDirectWriteLocked(Duration timeout) throws Exception {
        long remainingNanos = timeout.toNanos();
        while (!writes.isEmpty()) {
            if (health == PersistenceHealth.PAUSED || health == PersistenceHealth.STOPPED) {
                throw new PersistenceUnavailableException("Local persistence is not available for a durable integration write");
            }
            if (remainingNanos <= 0L) {
                throw new java.util.concurrent.TimeoutException("Timed out waiting for the local persistence queue to drain");
            }
            remainingNanos = queueDrained.awaitNanos(remainingNanos);
        }
        if (!acceptingWrites.get() || health == PersistenceHealth.DRAINING || health == PersistenceHealth.STOPPED) {
            throw new PersistenceUnavailableException("Local persistence is stopping");
        }
        recoveryStore.prepareForDirectAuthoritativeWrite();
    }

    private void applyAuthoritativeWalletRecordLocked(TransactionRecord record) {
        if (record == null) return;
        if (record.sourcePlayerId() != null && record.sourceBalanceAfterMinor() != null) {
            walletBalances.put(record.sourcePlayerId(), record.sourceBalanceAfterMinor());
        }
        if (record.targetPlayerId() != null && record.targetBalanceAfterMinor() != null) {
            walletBalances.put(record.targetPlayerId(), record.targetBalanceAfterMinor());
        }
    }

    @Override
    public TransactionRecord executeWalletTransaction(WalletTransactionRequest request, long maximumBalanceMinor)
            throws Exception {
        StorageAccessGuard.requireInternalCaller("AsyncWalletTransactionStore.executeWalletTransaction", AsyncWalletTransactionStore.class);
        requireMutable();
        Objects.requireNonNull(request, "request");
        if (maximumBalanceMinor < 0L) {
            throw new IllegalArgumentException("The maximum wallet balance must not be negative");
        }
        if (request.reversalOfTransactionId() != null && transactionStore.hasSuccessfulRollback(request.reversalOfTransactionId())) {
            return persistDuplicateRollback(request, () -> transactionStore.executeWalletTransaction(request,
                    maximumBalanceMinor));
        }
        stateLock.lock();
        try {
            TransactionRecord existing = existingPendingRequest(request);
            if (existing != null) {
                return existing;
            }
            if (request.reversalOfTransactionId() != null && pendingRollbacks.containsKey(request.reversalOfTransactionId())) {
                return recordFailureLocked(request, TransactionFailureReason.DUPLICATE_ROLLBACK,
                        "The referenced transaction has already been rolled back.", null, null);
            }
            if (!canAcceptWriteLocked()) {
                return persistenceUnavailableRecordLocked(request);
            }
            BalanceBackup walletBackup = BalanceBackup.capture(walletBalances, participantIds(request));
            TransactionRecord record = evaluateWalletMutation(request, maximumBalanceMinor);
            try {
                enqueueTransactionLocked(record,
                        () -> transactionStore.executeWalletTransaction(request, maximumBalanceMinor),
                        RecoveryDelta.transaction(record, Map.of()));
                return record;
            } catch (Exception exception) {
                walletBackup.restore(walletBalances);
                throw exception;
            }
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public TransactionRecord executeWalletBankTransaction(WalletTransactionRequest request, boolean mainToBank,
                                                           long maximumBalanceMinor) throws Exception {
        StorageAccessGuard.requireInternalCaller("AsyncWalletTransactionStore.executeWalletBankTransaction", AsyncWalletTransactionStore.class);
        requireMutable();
        Objects.requireNonNull(request, "request");
        if (maximumBalanceMinor < 0L) {
            throw new IllegalArgumentException("The maximum wallet balance must not be negative");
        }
        if (request.reversalOfTransactionId() != null && transactionStore.hasSuccessfulRollback(request.reversalOfTransactionId())) {
            return persistDuplicateRollback(request, () -> transactionStore.executeWalletBankTransaction(request,
                    mainToBank, maximumBalanceMinor));
        }
        stateLock.lock();
        try {
            TransactionRecord existing = existingPendingRequest(request);
            if (existing != null) {
                return existing;
            }
            if (request.reversalOfTransactionId() != null && pendingRollbacks.containsKey(request.reversalOfTransactionId())) {
                return recordFailureLocked(request, TransactionFailureReason.DUPLICATE_ROLLBACK,
                        "The referenced transaction has already been rolled back.", null, null);
            }
            validateWalletBankRequest(request, mainToBank);
            if (!canAcceptWriteLocked()) {
                return persistenceUnavailableRecordLocked(request);
            }
            UUID bankPlayerId = mainToBank ? request.sourcePlayerId() : request.targetPlayerId();
            BalanceBackup walletBackup = BalanceBackup.capture(walletBalances, participantIds(request));
            BalanceBackup bankBackup = BalanceBackup.capture(bankBalances, Set.of(bankPlayerId));
            TransactionRecord record = evaluateWalletBankMutation(request, mainToBank, maximumBalanceMinor);
            try {
                Map<UUID, Long> recoveryBankBalances = record.isSuccessful()
                        ? Map.of(bankPlayerId, bankBalances.get(bankPlayerId)) : Map.of();
                enqueueTransactionLocked(record,
                        () -> transactionStore.executeWalletBankTransaction(request, mainToBank, maximumBalanceMinor),
                        RecoveryDelta.transaction(record, recoveryBankBalances));
                return record;
            } catch (Exception exception) {
                walletBackup.restore(walletBalances);
                bankBackup.restore(bankBalances);
                throw exception;
            }
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public Optional<TransactionRecord> findTransaction(UUID transactionId) throws Exception {
        if (transactionId == null) {
            return Optional.empty();
        }
        stateLock.lock();
        try {
            TransactionRecord pending = pendingTransactions.get(transactionId);
            if (pending != null) {
                return Optional.of(pending);
            }
        } finally {
            stateLock.unlock();
        }
        return transactionStore.findTransaction(transactionId);
    }

    @Override
    public TransactionPage findTransactions(TransactionQuery query) throws Exception {
        return transactionStore.findTransactions(query);
    }

    @Override
    public EconomyStatistics getEconomyStatistics() throws Exception {
        return transactionStore.getEconomyStatistics();
    }

    @Override
    public EconomyAnalytics getEconomyAnalytics() throws Exception {
        return transactionStore.getEconomyAnalytics();
    }

    @Override
    public PlayerTransactionStatistics getPlayerTransactionStatistics(UUID playerId) throws Exception {
        return transactionStore.getPlayerTransactionStatistics(playerId);
    }

    @Override
    public List<TransactionRecord> getAllWalletTransactions() throws Exception {
        return transactionStore.getAllWalletTransactions();
    }

    @Override
    public boolean hasSuccessfulRollback(UUID originalTransactionId) throws Exception {
        if (originalTransactionId == null) {
            return false;
        }
        stateLock.lock();
        try {
            if (pendingRollbacks.containsKey(originalTransactionId)) {
                return true;
            }
        } finally {
            stateLock.unlock();
        }
        return transactionStore.hasSuccessfulRollback(originalTransactionId);
    }

    @Override
    public boolean isDebug() {
        return storage.isDebug();
    }

    /**
     * Stops accepting writes and drains the queue before releasing its worker. The underlying
     * storage is intentionally not closed here; StorageManager owns that lifecycle and closes it
     * only after this method returns.
     */
    @Override
    public void close() {
        if (!acceptingWrites.compareAndSet(true, false)) {
            return;
        }
        beginDrain();
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settings.shutdownTimeoutMillis());
        try {
            while (pendingWriteCount() > 0) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0L) {
                    plugin.getLogger().severe("[Persistence] Shutdown timeout reached with " + pendingWriteCount()
                            + " queued local write(s) still pending. The queue has stopped accepting mutations.");
                    break;
                }
                try {
                    long waitMillis = Math.min(settings.shutdownWarningIntervalMillis(),
                            TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                    if (waitMillis < 1L) waitMillis = 1L;
                    awaitPersistence(Duration.ofMillis(waitMillis));
                    if (pendingWriteCount() == 0) break;
                    plugin.getLogger().severe("[Persistence] Waiting for " + pendingWriteCount()
                            + " queued write(s) before storage shutdown.");
                    requestImmediateDrain();
                } catch (InterruptedException exception) {
                    interrupted = true;
                    plugin.getLogger().log(Level.SEVERE,
                            "[Persistence] Shutdown was interrupted while queued writes were still pending.", exception);
                }
            }
            worker.shutdown();
            try {
                long remainingNanos = Math.max(0L, deadline - System.nanoTime());
                if (!worker.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS)) {
                    plugin.getLogger().warning("[Persistence] The persistence worker did not terminate before the shutdown deadline.");
                    worker.shutdownNow();
                }
            } catch (InterruptedException exception) {
                interrupted = true;
                worker.shutdownNow();
            }
        } finally {
            stateLock.lock();
            try {
                if (!writes.isEmpty()) {
                    failUncommittedTransactionCompletionsLocked();
                }
                health = PersistenceHealth.STOPPED;
                nextAttemptAt = null;
                cancelScheduledDrainLocked();
                queueDrained.signalAll();
            } finally {
                stateLock.unlock();
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Must be called while {@link #stateLock} is held. */
    private void failUncommittedTransactionCompletionsLocked() {
        PersistenceUnavailableException failure = new PersistenceUnavailableException(
                "Local persistence stopped before every accepted transaction reached durable storage");
        for (PendingWrite write : writes) {
            if (write.completion() != null) {
                write.completion().completeExceptionally(failure);
            }
        }
    }

    private void beginDrain() {
        stateLock.lock();
        try {
            health = PersistenceHealth.DRAINING;
            nextAttemptAt = null;
            scheduleDrainLocked(0L, true);
        } finally {
            stateLock.unlock();
        }
    }

    private void reloadCache() throws Exception {
        Map<UUID, Long> loadedWallets = validatedMinorUnitMap(storage.getAllBalanceMinorUnits(),
                "persisted wallet balances");
        Map<UUID, Long> loadedBanks = validatedMinorUnitMap(storage.getAllBankBalanceMinorUnits(),
                "persisted bank balances");
        stateLock.lock();
        try {
            if (!writes.isEmpty()) {
                throw new IllegalStateException("Cannot reload the balance cache while persistence writes are pending");
            }
            walletBalances.clear();
            walletBalances.putAll(loadedWallets);
            bankBalances.clear();
            bankBalances.putAll(loadedBanks);
            pendingTransactions.clear();
            transactionPersistence.clear();
            accountPersistence.clear();
            pendingIdempotencyKeys.clear();
            pendingRollbacks.clear();
            if (health != PersistenceHealth.STOPPED) {
                health = acceptingWrites.get() ? PersistenceHealth.HEALTHY : PersistenceHealth.DRAINING;
                consecutiveFailures = 0;
                lastFailureAt = null;
                lastFailureSummary = null;
                nextAttemptAt = null;
                queueWarningLogged = false;
            }
        } finally {
            stateLock.unlock();
        }
    }

    private TransactionRecord evaluateWalletMutation(WalletTransactionRequest request, long maximumBalanceMinor) {
        return switch (request.operation()) {
            case CREDIT -> evaluateCredit(request, maximumBalanceMinor);
            case DEBIT -> evaluateDebit(request);
            case TRANSFER -> evaluateTransfer(request, maximumBalanceMinor);
            case SET -> evaluateSet(request, maximumBalanceMinor);
        };
    }

    private TransactionRecord evaluateCredit(WalletTransactionRequest request, long maximumBalanceMinor) {
        Long before = walletBalances.get(request.targetPlayerId());
        if (before == null) {
            return recordFailureLocked(request, TransactionFailureReason.ACCOUNT_NOT_FOUND,
                    "The target wallet account does not exist.", null, null);
        }
        Long after = addWithinMaximum(before, request.amountMinor(), maximumBalanceMinor);
        if (after == null) {
            return recordFailureLocked(request, TransactionFailureReason.MAXIMUM_BALANCE_EXCEEDED,
                    "The target wallet balance would exceed the configured maximum.", null, before);
        }
        walletBalances.put(request.targetPlayerId(), after);
        return TransactionRecords.successful(request, request.amountMinor(), null, null, before, after);
    }

    private TransactionRecord evaluateDebit(WalletTransactionRequest request) {
        Long before = walletBalances.get(request.sourcePlayerId());
        if (before == null) {
            return recordFailureLocked(request, TransactionFailureReason.ACCOUNT_NOT_FOUND,
                    "The source wallet account does not exist.", null, null);
        }
        if (before < request.amountMinor()) {
            return recordFailureLocked(request, TransactionFailureReason.INSUFFICIENT_FUNDS,
                    "The source wallet balance is insufficient.", before, null);
        }
        long after = before - request.amountMinor();
        walletBalances.put(request.sourcePlayerId(), after);
        return TransactionRecords.successful(request, request.amountMinor(), before, after, null, null);
    }

    private TransactionRecord evaluateTransfer(WalletTransactionRequest request, long maximumBalanceMinor) {
        Long sourceBefore = walletBalances.get(request.sourcePlayerId());
        Long targetBefore = walletBalances.get(request.targetPlayerId());
        if (sourceBefore == null || targetBefore == null) {
            return recordFailureLocked(request, TransactionFailureReason.ACCOUNT_NOT_FOUND,
                    "A referenced wallet account does not exist.", sourceBefore, targetBefore);
        }
        if (sourceBefore < request.amountMinor()) {
            return recordFailureLocked(request, TransactionFailureReason.INSUFFICIENT_FUNDS,
                    "The source wallet balance is insufficient.", sourceBefore, targetBefore);
        }
        Long targetAfter = addWithinMaximum(targetBefore, request.amountMinor(), maximumBalanceMinor);
        if (targetAfter == null) {
            return recordFailureLocked(request, TransactionFailureReason.MAXIMUM_BALANCE_EXCEEDED,
                    "The target wallet balance would exceed the configured maximum.", sourceBefore, targetBefore);
        }
        long sourceAfter = sourceBefore - request.amountMinor();
        walletBalances.put(request.sourcePlayerId(), sourceAfter);
        walletBalances.put(request.targetPlayerId(), targetAfter);
        return TransactionRecords.successful(request, request.amountMinor(), sourceBefore, sourceAfter,
                targetBefore, targetAfter);
    }

    private TransactionRecord evaluateSet(WalletTransactionRequest request, long maximumBalanceMinor) {
        Long before = walletBalances.get(request.targetPlayerId());
        if (before == null) {
            return recordFailureLocked(request, TransactionFailureReason.ACCOUNT_NOT_FOUND,
                    "The target wallet account does not exist.", null, null);
        }
        long after = Objects.requireNonNull(request.targetBalanceMinor(), "targetBalanceMinor");
        if (maximumBalanceMinor > 0L && after > maximumBalanceMinor) {
            return recordFailureLocked(request, TransactionFailureReason.MAXIMUM_BALANCE_EXCEEDED,
                    "The target wallet balance would exceed the configured maximum.", null, before);
        }
        long applied;
        try {
            applied = Math.abs(Math.subtractExact(after, before));
        } catch (ArithmeticException exception) {
            return recordFailureLocked(request, TransactionFailureReason.INVALID_AMOUNT,
                    "The resulting wallet balance change cannot be represented.", null, before);
        }
        walletBalances.put(request.targetPlayerId(), after);
        return TransactionRecords.successful(request, applied, null, null, before, after);
    }

    private TransactionRecord evaluateWalletBankMutation(WalletTransactionRequest request, boolean mainToBank,
                                                          long maximumBalanceMinor) {
        UUID playerId = mainToBank ? request.sourcePlayerId() : request.targetPlayerId();
        Long walletBefore = walletBalances.get(playerId);
        long bankBefore = bankBalances.getOrDefault(playerId, 0L);
        if (walletBefore == null) {
            return recordFailureLocked(request, TransactionFailureReason.ACCOUNT_NOT_FOUND,
                    "The wallet account does not exist for the bank transfer.",
                    mainToBank ? null : null, mainToBank ? null : null);
        }
        if (mainToBank) {
            if (walletBefore < request.amountMinor()) {
                return recordFailureLocked(request, TransactionFailureReason.INSUFFICIENT_FUNDS,
                        "The wallet account has insufficient funds for the bank deposit.", walletBefore, null);
            }
            long bankAfter;
            try {
                bankAfter = Math.addExact(bankBefore, request.amountMinor());
            } catch (ArithmeticException exception) {
                return recordFailureLocked(request, TransactionFailureReason.INVALID_AMOUNT,
                        "The resulting bank balance cannot be represented.", walletBefore, null);
            }
            long walletAfter = walletBefore - request.amountMinor();
            walletBalances.put(playerId, walletAfter);
            bankBalances.put(playerId, bankAfter);
            return TransactionRecords.successful(request, request.amountMinor(), walletBefore, walletAfter,
                    null, null);
        }
        if (bankBefore < request.amountMinor()) {
            return recordFailureLocked(request, TransactionFailureReason.INSUFFICIENT_FUNDS,
                    "The bank account has insufficient funds for the withdrawal.", null, walletBefore);
        }
        Long walletAfter = addWithinMaximum(walletBefore, request.amountMinor(), maximumBalanceMinor);
        if (walletAfter == null) {
            return recordFailureLocked(request, TransactionFailureReason.MAXIMUM_BALANCE_EXCEEDED,
                    "The wallet balance would exceed the configured maximum.", null, walletBefore);
        }
        walletBalances.put(playerId, walletAfter);
        bankBalances.put(playerId, bankBefore - request.amountMinor());
        return TransactionRecords.successful(request, request.amountMinor(), null, null, walletBefore, walletAfter);
    }

    private TransactionRecord persistDuplicateRollback(WalletTransactionRequest request, PersistenceAction action) throws Exception {
        stateLock.lock();
        try {
            if (!canAcceptWriteLocked()) {
                return persistenceUnavailableRecordLocked(request);
            }
            TransactionRecord record = recordFailureLocked(request, TransactionFailureReason.DUPLICATE_ROLLBACK,
                    "The referenced transaction has already been rolled back.",
                    request.sourcePlayerId() == null ? null : walletBalances.get(request.sourcePlayerId()),
                    request.targetPlayerId() == null ? null : walletBalances.get(request.targetPlayerId()));
            enqueueTransactionLocked(record, action, RecoveryDelta.transaction(record, Map.of()));
            return record;
        } finally {
            stateLock.unlock();
        }
    }

    private TransactionRecord recordFailureLocked(WalletTransactionRequest request, TransactionFailureReason reason,
                                                  String detail, Long sourceBalance, Long targetBalance) {
        return TransactionRecords.failed(request, reason, detail, sourceBalance, targetBalance);
    }

    private TransactionRecord persistenceUnavailableRecordLocked(WalletTransactionRequest request) {
        String detail = health == PersistenceHealth.PAUSED
                ? "Local persistence is paused after repeated storage failures."
                : health == PersistenceHealth.DRAINING || health == PersistenceHealth.STOPPED
                ? "Local persistence is stopping."
                : "The local persistence queue has reached its configured capacity.";
        return recordFailureLocked(request, TransactionFailureReason.STORAGE_FAILURE, detail,
                request.sourcePlayerId() == null ? null : walletBalances.get(request.sourcePlayerId()),
                request.targetPlayerId() == null ? null : walletBalances.get(request.targetPlayerId()));
    }

    private TransactionRecord existingPendingRequest(WalletTransactionRequest request) {
        TransactionRecord byId = pendingTransactions.get(request.id());
        if (byId != null) {
            return byId;
        }
        return request.idempotencyKey() == null ? null : pendingIdempotencyKeys.get(request.idempotencyKey());
    }

    private void enqueueTransactionLocked(TransactionRecord record, PersistenceAction action,
                                          RecoveryDelta recoveryDelta) throws Exception {
        pendingTransactions.put(record.id(), record);
        CompletableFuture<Void> completion = new CompletableFuture<>();
        transactionPersistence.put(record.id(), completion);
        if (record.idempotencyKey() != null) {
            pendingIdempotencyKeys.put(record.idempotencyKey(), record);
        }
        if (record.isSuccessful() && record.reversalOfTransactionId() != null) {
            pendingRollbacks.put(record.reversalOfTransactionId(), record);
        }
        try {
            enqueueLocked("wallet transaction " + record.id(), action, record, completion, recoveryDelta);
        } catch (Exception exception) {
            clearPendingTransactionLocked(record);
            completion.completeExceptionally(exception);
            throw exception;
        }
    }

    private void clearPendingTransactionLocked(TransactionRecord record) {
        pendingTransactions.remove(record.id());
        transactionPersistence.remove(record.id());
        if (record.idempotencyKey() != null) {
            pendingIdempotencyKeys.remove(record.idempotencyKey(), record);
        }
        if (record.isSuccessful() && record.reversalOfTransactionId() != null) {
            pendingRollbacks.remove(record.reversalOfTransactionId(), record);
        }
    }

    private void enqueueLocked(String description, PersistenceAction action, RecoveryDelta recoveryDelta) throws Exception {
        enqueueLocked(description, action, null, null, recoveryDelta);
    }

    private void enqueueLocked(String description, PersistenceAction action, TransactionRecord expectedTransaction,
                               CompletableFuture<Void> completion, RecoveryDelta recoveryDelta) throws Exception {
        requireWritableCapacityLocked();
        UUID writeId = UUID.randomUUID();
        Instant enqueuedAt = Instant.now();
        LocalRecoveryState recoveryState = recoveryDelta.toRecoveryState(writeId, enqueuedAt);
        PendingWrite write = new PendingWrite(writeId, description, action, expectedTransaction, completion,
                enqueuedAt);
        writes.addLast(write);
        try {
            // The compact WAL record is the synchronous durability barrier for locally accepted
            // mutations. Never schedule the backend write before this force has completed.
            recoveryStore.appendPending(recoveryState);
        } catch (Exception exception) {
            if (writes.peekLast() == write) {
                writes.removeLast();
            } else {
                writes.remove(write);
            }
            throw exception;
        }
        logQueueWarningIfRequiredLocked();
        scheduleDrainLocked(0L, false);
    }

    private void scheduleDrainLocked(long delayMillis, boolean replaceExistingSchedule) {
        if (writes.isEmpty() || health == PersistenceHealth.STOPPED || worker.isShutdown()) {
            return;
        }
        if (scheduledDrain != null && !scheduledDrain.isDone()) {
            if (!replaceExistingSchedule) {
                return;
            }
            scheduledDrain.cancel(false);
        }
        try {
            scheduledDrain = worker.schedule(this::drainOne, Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException exception) {
            scheduledDrain = null;
            throw exception;
        }
    }

    private void drainOne() {
        PendingWrite write;
        stateLock.lock();
        try {
            scheduledDrain = null;
            write = writes.peekFirst();
            if (write == null) {
                queueDrained.signalAll();
                return;
            }
        } finally {
            stateLock.unlock();
        }

        try {
            TransactionRecord persistedRecord = write.action().persist();
            write.verifyOutcome(persistedRecord);
            // Once the authoritative backend has committed, recovery cleanup is optional for
            // correctness: a stale absolute WAL record can be reconciled idempotently after a
            // crash. Mark it committed before allowing later queue entries to advance.
            recoveryStore.markCommitted(write.writeId());
            CompletableFuture<Void> completion;
            stateLock.lock();
            try {
                if (writes.peekFirst() != write) {
                    throw new IllegalStateException("Persistence queue ordering was corrupted");
                }
                writes.removeFirst();
                completion = write.completion();
                if (write.expectedTransaction() != null) {
                    clearPendingTransactionLocked(write.expectedTransaction());
                }
                markSuccessfulWriteLocked();
                queueDrained.signalAll();
                scheduleDrainLocked(0L, false);
            } finally {
                stateLock.unlock();
            }
            if (completion != null) {
                completion.complete(null);
            }
        } catch (PersistenceOutcomeMismatchException exception) {
            pauseForOutcomeMismatch(write, exception);
        } catch (Exception exception) {
            handleWriteFailure(write, exception);
        }
    }

    private void markSuccessfulWriteLocked() {
        if (health == PersistenceHealth.STOPPED) {
            // STOPPED is terminal. A backend call that returns after the shutdown deadline may
            // still have committed successfully, but it must never revive the closed queue.
            lastSuccessfulWriteAt = Instant.now();
            nextAttemptAt = null;
            if (writes.size() < warningThresholdCount()) {
                queueWarningLogged = false;
            }
            return;
        }
        boolean wasUnhealthy = health == PersistenceHealth.DEGRADED || health == PersistenceHealth.PAUSED;
        health = acceptingWrites.get() ? PersistenceHealth.HEALTHY : PersistenceHealth.DRAINING;
        consecutiveFailures = 0;
        lastSuccessfulWriteAt = Instant.now();
        lastFailureSummary = null;
        nextAttemptAt = null;
        if (wasUnhealthy) {
            plugin.getLogger().info("[Persistence] Local persistence recovered and the queue is processing normally.");
        }
        if (writes.size() < warningThresholdCount()) {
            queueWarningLogged = false;
        }
    }

    private void pauseForOutcomeMismatch(PendingWrite write, PersistenceOutcomeMismatchException exception) {
        stateLock.lock();
        try {
            if (health == PersistenceHealth.STOPPED) {
                // A late worker result must not move a closed queue back into a live health state.
                queueDrained.signalAll();
                return;
            }
            boolean draining = health == PersistenceHealth.DRAINING;
            health = draining ? PersistenceHealth.DRAINING : PersistenceHealth.PAUSED;
            consecutiveFailures++;
            lastFailureAt = Instant.now();
            lastFailureSummary = "The backend returned a transaction outcome different from the local accepted outcome.";
            if (draining) {
                long delay = Math.min(settings.initialRetryDelayMillis(), settings.shutdownWarningIntervalMillis());
                nextAttemptAt = Instant.now().plusMillis(delay);
                scheduleDrainLocked(delay, false);
            } else {
                nextAttemptAt = null;
            }
            queueDrained.signalAll();
        } finally {
            stateLock.unlock();
        }
        if (write.completion() != null) {
            write.completion().completeExceptionally(exception);
        }
        plugin.getLogger().log(Level.SEVERE, "[Persistence] Local persistence paused because " + write.description()
                + " produced a mismatched authoritative transaction outcome. No later write will be processed automatically.",
                exception);
    }

    private void handleWriteFailure(PendingWrite write, Exception exception) {
        int failedAttempt = write.recordFailure();
        boolean retryable = isRetryable(exception);
        long delay;
        boolean paused;
        stateLock.lock();
        try {
            if (health == PersistenceHealth.STOPPED) {
                // shutdownNow() may interrupt an in-flight backend call after close() has already
                // made STOPPED visible. Treat that state as terminal and ignore the late failure.
                queueDrained.signalAll();
                return;
            }
            consecutiveFailures++;
            lastFailureAt = Instant.now();
            lastFailureSummary = summarizeFailure(exception);
            boolean draining = health == PersistenceHealth.DRAINING;
            paused = !draining && (!retryable || failedAttempt >= settings.maximumRetryAttempts());
            if (draining) {
                delay = Math.min(settings.initialRetryDelayMillis(), settings.shutdownWarningIntervalMillis());
                nextAttemptAt = Instant.now().plusMillis(delay);
                scheduleDrainLocked(delay, false);
            } else if (paused) {
                health = PersistenceHealth.PAUSED;
                if (retryable && acceptingWrites.get()) {
                    delay = settings.recoveryProbeIntervalMillis();
                    nextAttemptAt = Instant.now().plusMillis(delay);
                    scheduleDrainLocked(delay, false);
                } else {
                    delay = 0L;
                    nextAttemptAt = null;
                }
                queueDrained.signalAll();
            } else {
                health = PersistenceHealth.DEGRADED;
                delay = retryDelayMillis(failedAttempt);
                nextAttemptAt = Instant.now().plusMillis(delay);
                scheduleDrainLocked(delay, false);
            }
        } finally {
            stateLock.unlock();
        }

        if (paused) {
            if (!retryable && write.completion() != null) {
                write.completion().completeExceptionally(new PersistenceUnavailableException(
                        "Local persistence permanently rejected " + write.description(), exception));
            }
            String action = retryable ? "will probe storage again in " + delay + " ms" : "requires administrator action";
            plugin.getLogger().log(Level.SEVERE, "[Persistence] Local persistence paused after " + failedAttempt
                    + " failed attempt(s) to commit " + write.description() + "; the queue " + action + ".", exception);
        } else if (failedAttempt == 1) {
            plugin.getLogger().log(Level.WARNING, "[Persistence] Could not commit " + write.description()
                    + "; retrying in " + delay + " ms (attempt " + failedAttempt + ").", exception);
        } else {
            plugin.getLogger().warning("[Persistence] Could not commit " + write.description()
                    + "; retrying in " + delay + " ms (attempt " + failedAttempt + "): " + summarizeFailure(exception));
        }
    }

    private long retryDelayMillis(int failedAttempt) {
        long delay = settings.initialRetryDelayMillis();
        for (int attempt = 1; attempt < failedAttempt && delay < settings.maximumRetryDelayMillis(); attempt++) {
            if (delay > settings.maximumRetryDelayMillis() / 2L) {
                return settings.maximumRetryDelayMillis();
            }
            delay *= 2L;
        }
        long cappedDelay = Math.min(delay, settings.maximumRetryDelayMillis());
        if (cappedDelay <= 1L) {
            return cappedDelay;
        }
        long lowerBound = Math.max(1L, cappedDelay / 2L);
        return ThreadLocalRandom.current().nextLong(lowerBound, cappedDelay + 1L);
    }

    private void requestImmediateDrain() {
        stateLock.lock();
        try {
            scheduleDrainLocked(0L, true);
        } finally {
            stateLock.unlock();
        }
    }

    private void awaitQueueBeforeDirectOperation() throws Exception {
        try {
            if (awaitPersistence(Duration.ofMillis(settings.shutdownTimeoutMillis()))) {
                recoveryStore.prepareForDirectAuthoritativeWrite();
                return;
            }
            throw new PersistenceUnavailableException("Local persistence could not drain before a direct storage operation");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for queued persistence", exception);
        }
    }

    private void requireMutable() {
        if (!acceptingWrites.get()) {
            throw new PersistenceUnavailableException("The asynchronous persistence queue is stopping");
        }
    }

    /** Must be called while {@link #stateLock} is held. */
    private boolean canAcceptWriteLocked() {
        return acceptingWrites.get() && health != PersistenceHealth.PAUSED
                && health != PersistenceHealth.DRAINING && health != PersistenceHealth.STOPPED
                && writes.size() < settings.queueCapacity();
    }

    /** Must be called while {@link #stateLock} is held and before changing a cached balance. */
    private void requireWritableCapacityLocked() {
        if (!acceptingWrites.get() || health == PersistenceHealth.DRAINING || health == PersistenceHealth.STOPPED) {
            throw new PersistenceUnavailableException("The asynchronous persistence queue is stopping");
        }
        if (health == PersistenceHealth.PAUSED) {
            throw new PersistenceUnavailableException("Local persistence is paused after repeated storage failures");
        }
        if (writes.size() >= settings.queueCapacity()) {
            throw new PersistenceQueueFullException("The local persistence queue has reached its configured capacity of "
                    + settings.queueCapacity());
        }
    }

    private void logQueueWarningIfRequiredLocked() {
        if (!queueWarningLogged && writes.size() >= warningThresholdCount()) {
            queueWarningLogged = true;
            plugin.getLogger().warning("[Persistence] Local persistence queue is at " + writes.size() + "/"
                    + settings.queueCapacity() + " pending write(s).");
        }
    }

    private int warningThresholdCount() {
        long threshold = (long) Math.ceil(settings.queueCapacity() * (settings.warningThresholdPercent() / 100.0D));
        return (int) Math.max(1L, Math.min(settings.queueCapacity(), threshold));
    }

    private void cancelScheduledDrainLocked() {
        if (scheduledDrain != null && !scheduledDrain.isDone()) {
            scheduledDrain.cancel(false);
        }
        scheduledDrain = null;
    }

    private static boolean isRetryable(Exception exception) {
        if (exception instanceof SQLTransientException || exception instanceof SQLRecoverableException
                || exception instanceof SQLTimeoutException || exception instanceof SocketTimeoutException
                || exception instanceof SocketException) {
            return true;
        }
        if (exception instanceof IOException) {
            return true;
        }
        // Storage backends may wrap short-lived filesystem or connection failures in an
        // IllegalStateException. The bounded retry policy prevents those wrappers from causing
        // an endless loop while still allowing one-off failures to recover automatically.
        return !(exception instanceof IllegalArgumentException || exception instanceof UnsupportedOperationException);
    }

    private static String summarizeFailure(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        String normalized = message.replaceAll("[\\r\\n]+", " ").trim();
        return normalized.length() <= 256 ? normalized : normalized.substring(0, 253) + "...";
    }

    private static void validateWalletBankRequest(WalletTransactionRequest request, boolean mainToBank) {
        if (mainToBank) {
            if (request.operation() != WalletOperation.DEBIT || request.sourcePlayerId() == null
                    || request.targetPlayerId() != null) {
                throw new IllegalArgumentException("A wallet-to-bank transaction must debit exactly one source wallet");
            }
        } else if (request.operation() != WalletOperation.CREDIT || request.sourcePlayerId() != null
                || request.targetPlayerId() == null) {
            throw new IllegalArgumentException("A bank-to-wallet transaction must credit exactly one target wallet");
        }
    }

    private static Long addWithinMaximum(long before, long amount, long maximumBalanceMinor) {
        try {
            long after = Math.addExact(before, amount);
            return maximumBalanceMinor > 0L && after > maximumBalanceMinor ? null : after;
        } catch (ArithmeticException exception) {
            return null;
        }
    }

    private static Map<UUID, Long> toMinorUnitMap(Map<UUID, Double> values, String name) {
        Objects.requireNonNull(values, name);
        Map<UUID, Long> converted = new LinkedHashMap<>();
        for (Map.Entry<UUID, Double> entry : values.entrySet()) {
            UUID playerId = Objects.requireNonNull(entry.getKey(), name + " contains a null player ID");
            Double balance = Objects.requireNonNull(entry.getValue(), name + " contains a null balance");
            if (!Money.isValid(balance) || balance < 0D) {
                throw new IllegalArgumentException(name + " contains an invalid balance");
            }
            converted.put(playerId, Money.toMinorUnits(balance));
        }
        return converted;
    }

    private static Map<UUID, Long> validatedMinorUnitMap(Map<UUID, Long> values, String name) {
        Objects.requireNonNull(values, name);
        Map<UUID, Long> validated = new LinkedHashMap<>();
        for (Map.Entry<UUID, Long> entry : values.entrySet()) {
            UUID playerId = Objects.requireNonNull(entry.getKey(), name + " contains a null player ID");
            Long balance = Objects.requireNonNull(entry.getValue(), name + " contains a null balance");
            if (balance < 0L) {
                throw new IllegalArgumentException(name + " contains a negative balance");
            }
            validated.put(playerId, balance);
        }
        return Map.copyOf(validated);
    }

    private static Map<UUID, Double> majorUnitCopy(Map<UUID, Long> values) {
        Map<UUID, Double> converted = new LinkedHashMap<>();
        values.forEach((playerId, amount) -> converted.put(playerId, Money.fromMinorUnits(amount)));
        return Map.copyOf(converted);
    }

    private static Set<UUID> participantIds(WalletTransactionRequest request) {
        Set<UUID> playerIds = new HashSet<>();
        if (request.sourcePlayerId() != null) playerIds.add(request.sourcePlayerId());
        if (request.targetPlayerId() != null) playerIds.add(request.targetPlayerId());
        return Set.copyOf(playerIds);
    }

    private static final class BalanceBackup {
        private final Map<UUID, Long> presentValues;
        private final Set<UUID> absentValues;

        private BalanceBackup(Map<UUID, Long> presentValues, Set<UUID> absentValues) {
            this.presentValues = presentValues;
            this.absentValues = absentValues;
        }

        private static BalanceBackup capture(Map<UUID, Long> balances, Set<UUID> playerIds) {
            Map<UUID, Long> present = new LinkedHashMap<>();
            Set<UUID> absent = new HashSet<>();
            for (UUID playerId : playerIds) {
                Long value = balances.get(playerId);
                if (value == null) absent.add(playerId);
                else present.put(playerId, value);
            }
            return new BalanceBackup(Map.copyOf(present), Set.copyOf(absent));
        }

        private void restore(Map<UUID, Long> balances) {
            for (UUID playerId : absentValues) balances.remove(playerId);
            balances.putAll(presentValues);
        }
    }

    private record RecoveryDelta(Map<UUID, Long> walletBalances, Map<UUID, Long> bankBalances,
                                 TransactionRecord walletTransaction,
                                 Map<UUID, LocalRecoveryState.AccountInitialization> accountInitializations) {
        private RecoveryDelta {
            walletBalances = Map.copyOf(walletBalances);
            bankBalances = Map.copyOf(bankBalances);
            accountInitializations = Map.copyOf(accountInitializations);
        }

        private static RecoveryDelta wallet(Map<UUID, Long> wallets) {
            return new RecoveryDelta(wallets, Map.of(), null, Map.of());
        }

        private static RecoveryDelta bank(Map<UUID, Long> banks) {
            return new RecoveryDelta(Map.of(), banks, null, Map.of());
        }

        private static RecoveryDelta walletAndBank(Map<UUID, Long> wallets, Map<UUID, Long> banks) {
            return new RecoveryDelta(wallets, banks, null, Map.of());
        }

        private static RecoveryDelta transaction(TransactionRecord record, Map<UUID, Long> banks) {
            Map<UUID, Long> wallets = new LinkedHashMap<>();
            if (record.isSuccessful()) {
                if (record.sourcePlayerId() != null && record.sourceBalanceAfterMinor() != null) {
                    wallets.put(record.sourcePlayerId(), record.sourceBalanceAfterMinor());
                }
                if (record.targetPlayerId() != null && record.targetBalanceAfterMinor() != null) {
                    wallets.put(record.targetPlayerId(), record.targetBalanceAfterMinor());
                }
            }
            return new RecoveryDelta(Map.copyOf(wallets), banks, record, Map.of());
        }

        private static RecoveryDelta accountInitialization(UUID playerId, long startingBalanceMinor,
                                                           LocalRecoveryState.AccountInitialization initialization) {
            return new RecoveryDelta(Map.of(playerId, startingBalanceMinor), Map.of(playerId, 0L), null,
                    Map.of(playerId, initialization));
        }

        private LocalRecoveryState toRecoveryState(UUID writeId, Instant enqueuedAt) {
            return LocalRecoveryState.of(writeId, enqueuedAt, walletBalances, bankBalances, walletTransaction,
                    accountInitializations);
        }
    }

    @FunctionalInterface
    private interface PersistenceAction {
        TransactionRecord persist() throws Exception;
    }

    private static final class PendingWrite {
        private final UUID writeId;
        private final String description;
        private final PersistenceAction action;
        private final TransactionRecord expectedTransaction;
        private final CompletableFuture<Void> completion;
        private final Instant enqueuedAt;
        private int failedAttempts;

        private PendingWrite(UUID writeId, String description, PersistenceAction action,
                             TransactionRecord expectedTransaction, CompletableFuture<Void> completion,
                             Instant enqueuedAt) {
            this.writeId = Objects.requireNonNull(writeId, "writeId");
            this.description = Objects.requireNonNull(description, "description");
            this.action = Objects.requireNonNull(action, "action");
            this.expectedTransaction = expectedTransaction;
            this.completion = completion;
            this.enqueuedAt = Objects.requireNonNull(enqueuedAt, "enqueuedAt");
        }

        private UUID writeId() {
            return writeId;
        }

        private String description() {
            return description;
        }

        private PersistenceAction action() {
            return action;
        }

        private TransactionRecord expectedTransaction() {
            return expectedTransaction;
        }

        private CompletableFuture<Void> completion() {
            return completion;
        }

        private Instant enqueuedAt() {
            return enqueuedAt;
        }

        private void verifyOutcome(TransactionRecord actual) {
            if (expectedTransaction == null) {
                return;
            }
            if (actual == null) {
                throw new IllegalStateException("The backend did not return a transaction record");
            }
            if (!matchesDurableOutcome(expectedTransaction, actual)) {
                throw new PersistenceOutcomeMismatchException(expectedTransaction, actual);
            }
        }

        /**
         * Verifies the durable transaction result, not the storage-specific wording attached to a
         * failed result. YAML, SQLite and MySQL may phrase the same failure differently, but the
         * authoritative outcome is identical when identifiers, status, balances, amounts and the
         * structured failure reason match. Treating only a detail-string difference as divergence
         * would pause local persistence even though no money state diverged.
         */
        private static boolean matchesDurableOutcome(TransactionRecord expected, TransactionRecord actual) {
            return Objects.equals(expected.id(), actual.id())
                    && Objects.equals(expected.timestamp(), actual.timestamp())
                    && expected.type() == actual.type()
                    && expected.status() == actual.status()
                    && Objects.equals(expected.origin(), actual.origin())
                    && expected.operation() == actual.operation()
                    && Objects.equals(expected.sourcePlayerId(), actual.sourcePlayerId())
                    && Objects.equals(expected.targetPlayerId(), actual.targetPlayerId())
                    && expected.requestedAmountMinor() == actual.requestedAmountMinor()
                    && expected.appliedAmountMinor() == actual.appliedAmountMinor()
                    && Objects.equals(expected.sourceBalanceBeforeMinor(), actual.sourceBalanceBeforeMinor())
                    && Objects.equals(expected.sourceBalanceAfterMinor(), actual.sourceBalanceAfterMinor())
                    && Objects.equals(expected.targetBalanceBeforeMinor(), actual.targetBalanceBeforeMinor())
                    && Objects.equals(expected.targetBalanceAfterMinor(), actual.targetBalanceAfterMinor())
                    && Objects.equals(expected.reason(), actual.reason())
                    && Objects.equals(expected.metadata(), actual.metadata())
                    && Objects.equals(expected.reversalOfTransactionId(), actual.reversalOfTransactionId())
                    && Objects.equals(expected.batchId(), actual.batchId())
                    && Objects.equals(expected.idempotencyKey(), actual.idempotencyKey())
                    && expected.failureReason() == actual.failureReason();
        }

        private int recordFailure() {
            return ++failedAttempts;
        }
    }

    private static final class PersistenceThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "SodaEconomy-Persistence");
            thread.setDaemon(true);
            return thread;
        }
    }
}
