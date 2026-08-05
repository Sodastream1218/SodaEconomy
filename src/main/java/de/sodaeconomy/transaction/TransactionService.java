package de.sodaeconomy.transaction;

import de.sodaeconomy.Money;
import de.sodaeconomy.SodaEconomy;
import de.sodaeconomy.storage.AsyncWalletTransactionStore;
import de.sodaeconomy.storage.BankInterestResult;
import de.sodaeconomy.storage.Storage;
import de.sodaeconomy.storage.WalletTransactionStore;
import org.bukkit.Bukkit;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

/**
 * Central coordinator for every wallet balance mutation. It validates requests, serializes local
 * operations, delegates the atomic mutation to the active storage layer, and emits a confirmed
 * transaction event on the Paper main thread only after the durability contract has completed.
 * YAML and SQLite may satisfy that contract through the ordered asynchronous persistence layer;
 * MySQL completes it directly through its database transaction.
 */
@SuppressWarnings("removal")
public final class TransactionService implements EconomyTransactionApi, AutoCloseable {
    private final SodaEconomy plugin;
    private final Storage storage;
    private final WalletTransactionStore store;
    private final long startingBalanceMinor;
    private final long maximumBalanceMinor;
    private final Clock clock;
    private final ReentrantLock transactionLock = new ReentrantLock(true);
    private final ExecutorService executor;
    private final AtomicBoolean acceptingRequests = new AtomicBoolean(true);

    public TransactionService(SodaEconomy plugin, WalletTransactionStore store, double startingBalance,
                              double maximumBalance) {
        this(plugin, store, startingBalance, maximumBalance, Clock.systemUTC());
    }

    TransactionService(SodaEconomy plugin, WalletTransactionStore store, double startingBalance,
                       double maximumBalance, Clock clock) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.store = Objects.requireNonNull(store, "store");
        this.storage = requireStorage(store);
        this.clock = Objects.requireNonNull(clock, "clock");
        if (!Money.isValid(startingBalance) || startingBalance < 0D) {
            throw new IllegalArgumentException("The starting balance must be finite and non-negative");
        }
        if (!Money.isValid(maximumBalance)) {
            throw new IllegalArgumentException("The maximum balance must be finite");
        }
        startingBalanceMinor = Money.toMinorUnits(startingBalance);
        maximumBalanceMinor = maximumBalance > 0D ? Money.toMinorUnits(maximumBalance) : 0L;
        if (maximumBalanceMinor > 0L && startingBalanceMinor > maximumBalanceMinor) {
            throw new IllegalArgumentException("The starting balance exceeds the configured maximum balance");
        }
        executor = Executors.newSingleThreadExecutor(new TransactionThreadFactory());
    }

    /** Returns a balance after atomically creating and journaling an initial account when required. */
    public double getBalanceSynchronously(UUID playerId) {
        requireInternalServiceCaller("TransactionService#getBalanceSynchronously");
        if (playerId == null) {
            return 0D;
        }
        transactionLock.lock();
        try {
            if (!acceptingRequests.get()) {
                return 0D;
            }
            return Money.fromMinorUnits(store.ensureWalletAccount(playerId, startingBalanceMinor, clock.instant()).balanceMinor());
        } catch (Exception exception) {
            logStorageFailure("read or create wallet account", exception);
            return 0D;
        } finally {
            transactionLock.unlock();
        }
    }

    /** Returns the current bank balance after ensuring the corresponding wallet account exists. */
    public double getBankBalanceSynchronously(UUID playerId) {
        requireInternalServiceCaller("TransactionService#getBankBalanceSynchronously");
        if (playerId == null) {
            return 0D;
        }
        transactionLock.lock();
        try {
            if (!acceptingRequests.get()) {
                return 0D;
            }
            store.ensureWalletAccount(playerId, startingBalanceMinor, clock.instant());
            Double balance = storage.getBankBalance(playerId);
            return balance == null ? 0D : Money.normalize(balance);
        } catch (Exception exception) {
            logStorageFailure("read or create bank account", exception);
            return 0D;
        } finally {
            transactionLock.unlock();
        }
    }

    /**
     * Coordinates a bank-only administrative balance update. Bank-only changes remain outside the
     * wallet journal until bank-ledger records are introduced, but they still share the service
     * lock with wallet-bank transfers and interest calculations.
     */
    public boolean setBankBalanceSynchronously(UUID playerId, double amount) {
        requireInternalServiceCaller("TransactionService#setBankBalanceSynchronously");
        if (playerId == null || !Money.isValid(amount) || amount < 0D) {
            return false;
        }
        transactionLock.lock();
        try {
            if (!acceptingRequests.get()) {
                return false;
            }
            store.ensureWalletAccount(playerId, startingBalanceMinor, clock.instant());
            storage.setBankBalance(playerId, Money.normalize(amount));
            return true;
        } catch (Exception exception) {
            logStorageFailure("set bank balance", exception);
            return false;
        } finally {
            transactionLock.unlock();
        }
    }

    /** Returns a defensive snapshot of persisted bank balances. */
    public Map<UUID, Double> getAllBankBalancesSynchronously() {
        requireInternalServiceCaller("TransactionService#getAllBankBalancesSynchronously");
        transactionLock.lock();
        try {
            if (!acceptingRequests.get()) {
                return Map.of();
            }
            return Map.copyOf(storage.getAllBankBalances());
        } catch (Exception exception) {
            logStorageFailure("read bank balances", exception);
            return Map.of();
        } finally {
            transactionLock.unlock();
        }
    }

    /**
     * Applies bank interest under the same service lock as wallet-bank transfers. The overload
     * without an interval is retained for source compatibility and performs one immediate run.
     */
    public int applyBankInterestSynchronously(double rate, double maximumInterest) {
        requireInternalServiceCaller("TransactionService#applyBankInterestSynchronously");
        return applyBankInterestSynchronously(rate, maximumInterest, Duration.ZERO);
    }

    /** Applies one locally serialized or MySQL-coordinated bank-interest interval. */
    public int applyBankInterestSynchronously(double rate, double maximumInterest, Duration minimumInterval) {
        requireInternalServiceCaller("TransactionService#applyBankInterestSynchronously");
        if (!Money.isPositive(rate) || !Money.isValid(maximumInterest) || maximumInterest < 0D
                || minimumInterval == null || minimumInterval.isNegative()) {
            return 0;
        }
        transactionLock.lock();
        try {
            if (!acceptingRequests.get()) {
                return 0;
            }
            long maximumInterestMinor = maximumInterest > 0D ? Money.toMinorUnits(maximumInterest) : 0L;
            BankInterestResult result = storage.applyBankInterest(BigDecimal.valueOf(rate), maximumInterestMinor,
                    minimumInterval, clock.instant());
            if (!result.executed()) {
                plugin.debugBanking("Skipped this interest invocation because another server already owns the interval.");
            }
            return result.changedAccounts();
        } catch (Exception exception) {
            logStorageFailure("apply bank interest", exception);
            return 0;
        } finally {
            transactionLock.unlock();
        }
    }

    public TransactionResult depositSynchronously(UUID targetPlayerId, double amount, TransactionType type,
                                                   TransactionOrigin origin, String reason,
                                                   Map<String, String> metadata) {
        requireInternalServiceCaller("TransactionService#depositSynchronously");
        return depositSynchronously(targetPlayerId, amount, type, origin, reason, metadata, null);
    }

    /** Executes a credit synchronously with an optional shared audit batch identifier. */
    public TransactionResult depositSynchronously(UUID targetPlayerId, double amount, TransactionType type,
                                                   TransactionOrigin origin, String reason,
                                                   Map<String, String> metadata, UUID batchId) {
        requireInternalServiceCaller("TransactionService#depositSynchronously");
        if (!isValidGenericType(type, WalletOperation.CREDIT) || targetPlayerId == null || origin == null) {
            return invalidRequest();
        }
        if (!isPositiveAmountRepresentable(amount)) {
            return invalidAmount();
        }
        return executeBuiltRequest(() -> requestForCredit(targetPlayerId, amount, type, origin, reason, metadata,
                null, batchId));
    }

    public TransactionResult withdrawSynchronously(UUID sourcePlayerId, double amount, TransactionType type,
                                                    TransactionOrigin origin, String reason,
                                                    Map<String, String> metadata) {
        requireInternalServiceCaller("TransactionService#withdrawSynchronously");
        return withdrawSynchronously(sourcePlayerId, amount, type, origin, reason, metadata, null);
    }

    /** Executes a debit synchronously with an optional shared audit batch identifier. */
    public TransactionResult withdrawSynchronously(UUID sourcePlayerId, double amount, TransactionType type,
                                                    TransactionOrigin origin, String reason,
                                                    Map<String, String> metadata, UUID batchId) {
        requireInternalServiceCaller("TransactionService#withdrawSynchronously");
        if (!isValidGenericType(type, WalletOperation.DEBIT) || sourcePlayerId == null || origin == null) {
            return invalidRequest();
        }
        if (!isPositiveAmountRepresentable(amount)) {
            return invalidAmount();
        }
        return executeBuiltRequest(() -> requestForDebit(sourcePlayerId, amount, type, origin, reason, metadata,
                null, batchId));
    }

    public TransactionResult transferSynchronously(UUID sourcePlayerId, UUID targetPlayerId, double amount,
                                                    TransactionType type, TransactionOrigin origin, String reason,
                                                    Map<String, String> metadata) {
        requireInternalServiceCaller("TransactionService#transferSynchronously");
        return transferSynchronously(sourcePlayerId, targetPlayerId, amount, type, origin, reason, metadata, null);
    }

    /** Executes a transfer synchronously with an optional shared audit batch identifier. */
    public TransactionResult transferSynchronously(UUID sourcePlayerId, UUID targetPlayerId, double amount,
                                                    TransactionType type, TransactionOrigin origin, String reason,
                                                    Map<String, String> metadata, UUID batchId) {
        requireInternalServiceCaller("TransactionService#transferSynchronously");
        if (!isValidGenericType(type, WalletOperation.TRANSFER) || sourcePlayerId == null || targetPlayerId == null
                || sourcePlayerId.equals(targetPlayerId) || origin == null) {
            return invalidRequest();
        }
        if (!isPositiveAmountRepresentable(amount)) {
            return invalidAmount();
        }
        return executeBuiltRequest(() -> requestForTransfer(sourcePlayerId, targetPlayerId, amount, type, origin,
                reason, metadata, null, batchId));
    }

    public TransactionResult setBalanceSynchronously(UUID targetPlayerId, double targetBalance, TransactionType type,
                                                      TransactionOrigin origin, String reason,
                                                      Map<String, String> metadata) {
        requireInternalServiceCaller("TransactionService#setBalanceSynchronously");
        return setBalanceSynchronously(targetPlayerId, targetBalance, type, origin, reason, metadata, null);
    }

    /** Executes an absolute balance set synchronously with an optional shared audit batch identifier. */
    public TransactionResult setBalanceSynchronously(UUID targetPlayerId, double targetBalance, TransactionType type,
                                                      TransactionOrigin origin, String reason,
                                                      Map<String, String> metadata, UUID batchId) {
        requireInternalServiceCaller("TransactionService#setBalanceSynchronously");
        if (!isValidGenericType(type, WalletOperation.SET) || targetPlayerId == null || origin == null) {
            return invalidRequest();
        }
        if (!isNonNegativeAmountRepresentable(targetBalance)) {
            return invalidAmount();
        }
        return executeBuiltRequest(() -> requestForSet(targetPlayerId, targetBalance, type, origin, reason, metadata,
                null, batchId));
    }

    /**
     * Moves money between a player's wallet and bank account through the same serialized,
     * atomic wallet-journal boundary as every other wallet mutation.
     */
    public TransactionResult transferWalletAndBankSynchronously(UUID playerId, boolean mainToBank, double amount,
                                                                  TransactionOrigin origin, String reason,
                                                                  Map<String, String> metadata) {
        requireInternalServiceCaller("TransactionService#transferWalletAndBankSynchronously");
        if (playerId == null || origin == null) {
            return invalidRequest();
        }
        if (!isPositiveAmountRepresentable(amount)) {
            return invalidAmount();
        }
        return executeBuiltBankRequest(() -> mainToBank
                ? requestForDebit(playerId, amount, TransactionType.WALLET_TO_BANK, origin, reason, metadata, null, null)
                : requestForCredit(playerId, amount, TransactionType.BANK_TO_WALLET, origin, reason, metadata, null, null),
                mainToBank);
    }

    /**
     * Performs the account lookup and possible initial-account creation outside the Paper main
     * thread. Commands should use this method for database-backed storage.
     */
    public CompletableFuture<Double> getBalanceAsynchronously(UUID playerId) {
        requireInternalServiceCaller("TransactionService#getBalanceAsynchronously");
        return submitAsync(() -> runInternalServicePath(() -> getBalanceSynchronously(playerId)));
    }

    /** Performs a bank balance lookup outside the Paper main thread. */
    public CompletableFuture<Double> getBankBalanceAsynchronously(UUID playerId) {
        requireInternalServiceCaller("TransactionService#getBankBalanceAsynchronously");
        return submitAsync(() -> runInternalServicePath(() -> getBankBalanceSynchronously(playerId)));
    }

    /** Updates a bank-only administrative balance outside the Paper main thread. */
    public CompletableFuture<Boolean> setBankBalanceAsynchronously(UUID playerId, double amount) {
        requireInternalServiceCaller("TransactionService#setBankBalanceAsynchronously");
        return submitAsync(() -> runInternalServicePath(() -> setBankBalanceSynchronously(playerId, amount)));
    }

    /** Returns all persisted wallet balances outside the Paper main thread. */
    public CompletableFuture<Map<UUID, Double>> getAllBalancesAsynchronously() {
        requireInternalServiceCaller("TransactionService#getAllBalancesAsynchronously");
        return submitAsync(() -> {
            transactionLock.lock();
            try {
                return acceptingRequests.get() ? Map.copyOf(storage.getAllBalances()) : Map.of();
            } catch (Exception exception) {
                logStorageFailure("read wallet balances", exception);
                return Map.of();
            } finally {
                transactionLock.unlock();
            }
        });
    }

    /** Queues a built-in credit without exposing the command origin through the public API. */
    public CompletableFuture<TransactionResult> depositAsynchronously(UUID targetPlayerId, double amount,
                                                                        TransactionType type, TransactionOrigin origin,
                                                                        String reason, Map<String, String> metadata) {
        requireInternalServiceCaller("TransactionService#depositAsynchronously");
        return depositAsynchronously(targetPlayerId, amount, type, origin, reason, metadata, null);
    }

    /** Queues a built-in credit with a shared audit batch identifier. */
    public CompletableFuture<TransactionResult> depositAsynchronously(UUID targetPlayerId, double amount,
                                                                        TransactionType type, TransactionOrigin origin,
                                                                        String reason, Map<String, String> metadata,
                                                                        UUID batchId) {
        requireInternalServiceCaller("TransactionService#depositAsynchronously");
        return submitPersistedResult(() -> runInternalServicePath(() -> depositSynchronously(targetPlayerId, amount, type, origin, reason, metadata,
                batchId)));
    }

    /** Queues a built-in debit without exposing the command origin through the public API. */
    public CompletableFuture<TransactionResult> withdrawAsynchronously(UUID sourcePlayerId, double amount,
                                                                         TransactionType type, TransactionOrigin origin,
                                                                         String reason, Map<String, String> metadata) {
        requireInternalServiceCaller("TransactionService#withdrawAsynchronously");
        return submitPersistedResult(() -> runInternalServicePath(() -> withdrawSynchronously(sourcePlayerId, amount, type, origin, reason, metadata)));
    }

    /** Queues a built-in wallet transfer and completes only after the active persistence mode confirms it. */
    public CompletableFuture<TransactionResult> transferAsynchronously(UUID sourcePlayerId, UUID targetPlayerId,
                                                                         double amount, TransactionType type,
                                                                         TransactionOrigin origin, String reason,
                                                                         Map<String, String> metadata) {
        requireInternalServiceCaller("TransactionService#transferAsynchronously");
        return submitPersistedResult(() -> runInternalServicePath(() -> transferSynchronously(sourcePlayerId, targetPlayerId, amount, type, origin,
                reason, metadata)));
    }

    /** Queues a built-in absolute balance update. */
    public CompletableFuture<TransactionResult> setBalanceAsynchronously(UUID targetPlayerId, double targetBalance,
                                                                           TransactionType type, TransactionOrigin origin,
                                                                           String reason, Map<String, String> metadata) {
        requireInternalServiceCaller("TransactionService#setBalanceAsynchronously");
        return submitPersistedResult(() -> runInternalServicePath(() -> setBalanceSynchronously(targetPlayerId, targetBalance, type, origin, reason,
                metadata)));
    }

    /** Queues a wallet-bank movement through the same durable transaction boundary. */
    public CompletableFuture<TransactionResult> transferWalletAndBankAsynchronously(UUID playerId, boolean mainToBank,
                                                                                       double amount, TransactionOrigin origin,
                                                                                       String reason,
                                                                                       Map<String, String> metadata) {
        requireInternalServiceCaller("TransactionService#transferWalletAndBankAsynchronously");
        return submitPersistedResult(() -> runInternalServicePath(() -> transferWalletAndBankSynchronously(playerId, mainToBank, amount, origin,
                reason, metadata)));
    }

    /**
     * Internal durable credit used by synchronous compatibility adapters. The returned handle may
     * be cancelled only while it is still queued; after execution begins its definitive committed
     * result must be observed.
     */
    public DurableOperation<TransactionResult> depositForIntegration(UUID targetPlayerId, double amount,
                                                                       TransactionOrigin origin, String reason,
                                                                       Map<String, String> metadata,
                                                                       Duration localAdmissionTimeout) {
        requireInternalServiceCaller("TransactionService#depositForIntegration");
        if (targetPlayerId == null || !isApiOrigin(origin) || !isPositiveAmountRepresentable(amount)) {
            return completedDurable(!isPositiveAmountRepresentable(amount) ? invalidAmount() : invalidRequest());
        }
        return submitDurable(() -> executeIntegrationRequest(() -> requestForCredit(targetPlayerId, amount,
                TransactionType.API_DEPOSIT, origin, reason, metadata, null, null), localAdmissionTimeout));
    }

    /** Durable debit counterpart for synchronous compatibility adapters. */
    public DurableOperation<TransactionResult> withdrawForIntegration(UUID sourcePlayerId, double amount,
                                                                        TransactionOrigin origin, String reason,
                                                                        Map<String, String> metadata,
                                                                        Duration localAdmissionTimeout) {
        requireInternalServiceCaller("TransactionService#withdrawForIntegration");
        if (sourcePlayerId == null || !isApiOrigin(origin) || !isPositiveAmountRepresentable(amount)) {
            return completedDurable(!isPositiveAmountRepresentable(amount) ? invalidAmount() : invalidRequest());
        }
        return submitDurable(() -> executeIntegrationRequest(() -> requestForDebit(sourcePlayerId, amount,
                TransactionType.API_WITHDRAW, origin, reason, metadata, null, null), localAdmissionTimeout));
    }

    /** Reads account existence and balance without creating a wallet and without swallowing errors. */
    public CompletableFuture<WalletAccountLookup> lookupAccountForIntegration(UUID playerId) {
        requireInternalServiceCaller("TransactionService#lookupAccountForIntegration");
        if (playerId == null) return CompletableFuture.completedFuture(new WalletAccountLookup(false, 0L));
        return submitAsync(() -> {
            transactionLock.lock();
            try {
                Long balanceMinor = storage.getBalanceMinorUnits(playerId);
                if (balanceMinor == null) return new WalletAccountLookup(false, 0L);
                if (balanceMinor < 0L) throw new IllegalStateException("Stored wallet balance must not be negative");
                return new WalletAccountLookup(true, balanceMinor);
            } catch (Exception exception) {
                throw new java.util.concurrent.CompletionException(exception);
            } finally {
                transactionLock.unlock();
            }
        });
    }

    /** Creates a wallet account and completes only after its opening balance is durable. */
    public DurableOperation<WalletAccountState> createAccountForIntegration(UUID playerId,
                                                                             Duration localAdmissionTimeout) {
        requireInternalServiceCaller("TransactionService#createAccountForIntegration");
        if (playerId == null) {
            DurableOperation<WalletAccountState> invalid = new DurableOperation<>();
            invalid.completeExceptionally(new IllegalArgumentException("The player ID must not be null"));
            return invalid;
        }
        return submitDurable(() -> {
            transactionLock.lock();
            try {
                if (!acceptingRequests.get()) throw new IllegalStateException("The transaction service is stopping");
                if (store instanceof AsyncWalletTransactionStore asynchronousStore) {
                    return asynchronousStore.ensureWalletAccountDurably(playerId, startingBalanceMinor, now(),
                            Objects.requireNonNull(localAdmissionTimeout, "localAdmissionTimeout"));
                }
                return store.ensureWalletAccount(playerId, startingBalanceMinor, now());
            } catch (Exception exception) {
                throw new java.util.concurrent.CompletionException(exception);
            } finally {
                transactionLock.unlock();
            }
        });
    }

    @Override
    public CompletableFuture<TransactionResult> deposit(UUID targetPlayerId, double amount, TransactionType type,
                                                         TransactionOrigin origin, String reason,
                                                         Map<String, String> metadata) {
        if (type != TransactionType.API_DEPOSIT || !isApiOrigin(origin)) {
            return CompletableFuture.completedFuture(invalidRequest());
        }
        return submitPersistedResult(() -> runInternalServicePath(() -> depositSynchronously(targetPlayerId, amount, type, origin, reason, metadata)));
    }

    @Override
    public CompletableFuture<TransactionResult> withdraw(UUID sourcePlayerId, double amount, TransactionType type,
                                                          TransactionOrigin origin, String reason,
                                                          Map<String, String> metadata) {
        if (type != TransactionType.API_WITHDRAW || !isApiOrigin(origin)) {
            return CompletableFuture.completedFuture(invalidRequest());
        }
        return submitPersistedResult(() -> runInternalServicePath(() -> withdrawSynchronously(sourcePlayerId, amount, type, origin, reason, metadata)));
    }

    @Override
    public CompletableFuture<TransactionResult> transfer(UUID sourcePlayerId, UUID targetPlayerId, double amount,
                                                          TransactionType type, TransactionOrigin origin, String reason,
                                                          Map<String, String> metadata) {
        if (type != TransactionType.API_TRANSFER || !isApiOrigin(origin)) {
            return CompletableFuture.completedFuture(invalidRequest());
        }
        return submitPersistedResult(() -> runInternalServicePath(() -> transferSynchronously(sourcePlayerId, targetPlayerId, amount, type, origin, reason, metadata)));
    }

    @Override
    public CompletableFuture<TransactionResult> setBalance(UUID targetPlayerId, double targetBalance, TransactionType type,
                                                            TransactionOrigin origin, String reason,
                                                            Map<String, String> metadata) {
        if (type != TransactionType.API_SET || !isApiOrigin(origin)) {
            return CompletableFuture.completedFuture(invalidRequest());
        }
        return submitPersistedResult(() -> runInternalServicePath(() -> setBalanceSynchronously(targetPlayerId, targetBalance, type, origin, reason, metadata)));
    }

    @Override
    public CompletableFuture<Double> getStoredBalance(UUID playerId) {
        return runInternalServicePath(() -> getStoredBalanceAsynchronously(playerId));
    }

    @Override
    public CompletableFuture<TransactionResult> deposit(UUID targetPlayerId, double amount, TransactionOrigin origin,
                                                         TransactionRequestOptions options) {
        if (!isApiOrigin(origin) || !isPositiveAmountRepresentable(amount)) {
            return CompletableFuture.completedFuture(!isApiOrigin(origin) ? invalidRequest() : invalidAmount());
        }
        TransactionRequestOptions requestOptions = Objects.requireNonNull(options, "options");
        return submitPersistedResult(() -> executeBuiltRequest(() -> requestForCredit(targetPlayerId, amount,
                TransactionType.API_DEPOSIT, origin, requestOptions.reason(), requestOptions.metadata(), null, null,
                requestOptions.idempotencyKey())));
    }

    @Override
    public CompletableFuture<TransactionResult> withdraw(UUID sourcePlayerId, double amount, TransactionOrigin origin,
                                                          TransactionRequestOptions options) {
        if (!isApiOrigin(origin) || !isPositiveAmountRepresentable(amount)) {
            return CompletableFuture.completedFuture(!isApiOrigin(origin) ? invalidRequest() : invalidAmount());
        }
        TransactionRequestOptions requestOptions = Objects.requireNonNull(options, "options");
        return submitPersistedResult(() -> executeBuiltRequest(() -> requestForDebit(sourcePlayerId, amount,
                TransactionType.API_WITHDRAW, origin, requestOptions.reason(), requestOptions.metadata(), null, null,
                requestOptions.idempotencyKey())));
    }

    @Override
    public CompletableFuture<TransactionResult> transfer(UUID sourcePlayerId, UUID targetPlayerId, double amount,
                                                          TransactionOrigin origin, TransactionRequestOptions options) {
        if (!isApiOrigin(origin) || !isPositiveAmountRepresentable(amount)) {
            return CompletableFuture.completedFuture(!isApiOrigin(origin) ? invalidRequest() : invalidAmount());
        }
        TransactionRequestOptions requestOptions = Objects.requireNonNull(options, "options");
        return submitPersistedResult(() -> executeBuiltRequest(() -> requestForTransfer(sourcePlayerId, targetPlayerId,
                amount, TransactionType.API_TRANSFER, origin, requestOptions.reason(), requestOptions.metadata(), null,
                null, requestOptions.idempotencyKey())));
    }

    @Override
    public CompletableFuture<TransactionResult> setBalance(UUID targetPlayerId, double targetBalance,
                                                            TransactionOrigin origin, TransactionRequestOptions options) {
        if (!isApiOrigin(origin) || !isNonNegativeAmountRepresentable(targetBalance)) {
            return CompletableFuture.completedFuture(!isApiOrigin(origin) ? invalidRequest() : invalidAmount());
        }
        TransactionRequestOptions requestOptions = Objects.requireNonNull(options, "options");
        return submitPersistedResult(() -> executeBuiltRequest(() -> requestForSet(targetPlayerId, targetBalance,
                TransactionType.API_SET, origin, requestOptions.reason(), requestOptions.metadata(), null, null,
                requestOptions.idempotencyKey())));
    }

    @Override
    public CompletableFuture<TransactionResult> rollback(UUID transactionId, TransactionOrigin origin, String reason) {
        if (!isApiOrigin(origin)) {
            return CompletableFuture.completedFuture(invalidRequest());
        }
        return submitPersistedResult(() -> runInternalServicePath(() -> rollbackSynchronously(transactionId, origin, reason)));
    }

    /**
     * Queues a rollback requested by a built-in command. This deliberately remains separate from
     * the public API method above: external integrations may only identify themselves as API
     * callers, while the command retains its accurate player or console audit origin.
     */
    public CompletableFuture<TransactionResult> rollbackAsynchronously(UUID transactionId, TransactionOrigin origin,
                                                                         String reason) {
        requireInternalServiceCaller("TransactionService#rollbackAsynchronously");
        if (transactionId == null || origin == null) {
            return CompletableFuture.completedFuture(invalidRequest());
        }
        return submitPersistedResult(() -> runInternalServicePath(() -> rollbackSynchronously(transactionId, origin, reason)));
    }

    public TransactionResult rollbackSynchronously(UUID transactionId, TransactionOrigin origin, String reason) {
        requireInternalServiceCaller("TransactionService#rollbackSynchronously");
        if (transactionId == null || origin == null) {
            return TransactionResult.failure(null, TransactionFailureReason.INVALID_REQUEST);
        }
        transactionLock.lock();
        try {
            if (!acceptingRequests.get()) {
                return TransactionResult.failure(null, TransactionFailureReason.SERVICE_STOPPING);
            }
            Optional<TransactionRecord> originalResult = store.findTransaction(transactionId);
            if (originalResult.isEmpty() || !originalResult.get().isSuccessful()) {
                return TransactionResult.failure(null, TransactionFailureReason.NOT_REVERSIBLE);
            }
            WalletTransactionRequest reversal = buildReversal(originalResult.get(), origin, reason);
            ensureParticipants(reversal);
            TransactionRecord record = isWalletBankTransaction(originalResult.get())
                    ? store.executeWalletBankTransaction(reversal,
                    reversal.operation() == WalletOperation.DEBIT, maximumBalanceMinor)
                    : store.executeWalletTransaction(reversal, maximumBalanceMinor);
            publishAfterAcceptance(record);
            return record.isSuccessful()
                    ? TransactionResult.success(record)
                    : TransactionResult.failure(record, record.failureReason());
        } catch (IllegalArgumentException exception) {
            return TransactionResult.failure(null, TransactionFailureReason.INVALID_REQUEST);
        } catch (Exception exception) {
            logStorageFailure("roll back wallet transaction", exception);
            return TransactionResult.failure(null, TransactionFailureReason.STORAGE_FAILURE);
        } finally {
            transactionLock.unlock();
        }
    }

    @Override
    public CompletableFuture<TransactionPage> findTransactions(TransactionQuery query) {
        return submitAsync(() -> {
            try {
                return store.findTransactions(Objects.requireNonNullElseGet(query, TransactionQuery::recent));
            } catch (Exception exception) {
                logStorageFailure("query wallet transaction history", exception);
                return new TransactionPage(java.util.List.of(), 0, TransactionQuery.DEFAULT_LIMIT, false);
            }
        });
    }

    @Override
    public CompletableFuture<EconomyStatistics> getStatistics() {
        return submitAsync(() -> {
            try {
                return store.getEconomyStatistics();
            } catch (Exception exception) {
                logStorageFailure("query economy statistics", exception);
                return new EconomyStatistics(0L, 0L, null, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
            }
        });
    }

    @Override
    public CompletableFuture<EconomyAnalytics> getAnalytics() {
        return submitAsync(() -> {
            try {
                return store.getEconomyAnalytics();
            } catch (Exception exception) {
                logStorageFailure("query extended economy statistics", exception);
                return emptyAnalytics();
            }
        });
    }

    @Override
    public CompletableFuture<PlayerTransactionStatistics> getPlayerStatistics(UUID playerId) {
        if (playerId == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("The player ID must not be null"));
        }
        return submitAsync(() -> {
            try {
                return store.getPlayerTransactionStatistics(playerId);
            } catch (Exception exception) {
                logStorageFailure("query player transaction statistics", exception);
                return emptyPlayerStatistics(playerId);
            }
        });
    }

    /**
     * Reads an already stored wallet balance without creating an account. This is intended for
     * asynchronous administrative views, where a read must not change the economy state.
     */
    public CompletableFuture<Double> getStoredBalanceAsynchronously(UUID playerId) {
        requireInternalServiceCaller("TransactionService#getStoredBalanceAsynchronously");
        if (playerId == null) {
            return CompletableFuture.completedFuture(0D);
        }
        return submitAsync(() -> {
            try {
                Double balance = storage.getBalance(playerId);
                if (balance == null) {
                    return 0D;
                }
                if (!Money.isValid(balance) || balance < 0D) {
                    throw new IllegalStateException("The stored wallet balance is invalid for " + playerId);
                }
                return Money.normalize(balance);
            } catch (Exception exception) {
                logStorageFailure("query stored wallet balance", exception);
                return 0D;
            }
        });
    }

    public EconomyStatistics getStatisticsSynchronously() {
        requireInternalServiceCaller("TransactionService#getStatisticsSynchronously");
        try {
            return store.getEconomyStatistics();
        } catch (Exception exception) {
            logStorageFailure("query economy statistics", exception);
            return new EconomyStatistics(0L, 0L, null, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        }
    }

    private static EconomyAnalytics emptyAnalytics() {
        return new EconomyAnalytics(new EconomyStatistics(0L, 0L, null, 0L, 0L, 0L, 0L, 0L, 0L, 0L), 0L, 0L);
    }

    private static PlayerTransactionStatistics emptyPlayerStatistics(UUID playerId) {
        return new PlayerTransactionStatistics(playerId, 0L, 0L, 0L, 0L, 0L, null, null);
    }

    private TransactionResult executeIntegrationRequest(RequestBuilder builder, Duration localAdmissionTimeout) {
        try {
            WalletTransactionRequest request = builder.build();
            transactionLock.lock();
            try {
                if (!acceptingRequests.get()) {
                    return TransactionResult.failure(null, TransactionFailureReason.SERVICE_STOPPING);
                }
                if (!request.type().isGenericWalletMutation()) return invalidRequest();
                if (store instanceof AsyncWalletTransactionStore asynchronousStore) {
                    ensureParticipantsDurably(request, asynchronousStore, localAdmissionTimeout);
                    TransactionRecord record = asynchronousStore.executeWalletTransactionDurably(request,
                            maximumBalanceMinor, localAdmissionTimeout);
                    publishAfterAcceptance(record);
                    return record.isSuccessful() ? TransactionResult.success(record)
                            : TransactionResult.failure(record, record.failureReason());
                }
                ensureParticipants(request);
                TransactionRecord record = store.executeWalletTransaction(request, maximumBalanceMinor);
                publishAfterAcceptance(record);
                return record.isSuccessful() ? TransactionResult.success(record)
                        : TransactionResult.failure(record, record.failureReason());
            } finally {
                transactionLock.unlock();
            }
        } catch (IllegalArgumentException exception) {
            return invalidRequest();
        } catch (java.util.concurrent.TimeoutException exception) {
            return TransactionResult.failure(null, TransactionFailureReason.STORAGE_FAILURE);
        } catch (Exception exception) {
            logStorageFailure("commit durable integration transaction", exception);
            return TransactionResult.failure(null, TransactionFailureReason.STORAGE_FAILURE);
        }
    }

    private void ensureParticipantsDurably(WalletTransactionRequest request,
                                            AsyncWalletTransactionStore asynchronousStore,
                                            Duration localAdmissionTimeout) throws Exception {
        Duration timeout = Objects.requireNonNull(localAdmissionTimeout, "localAdmissionTimeout");
        if (request.sourcePlayerId() != null) {
            asynchronousStore.ensureWalletAccountDurably(request.sourcePlayerId(), startingBalanceMinor,
                    request.timestamp(), timeout);
        }
        if (request.targetPlayerId() != null) {
            asynchronousStore.ensureWalletAccountDurably(request.targetPlayerId(), startingBalanceMinor,
                    request.timestamp(), timeout);
        }
    }

    private TransactionResult executeBuiltRequest(RequestBuilder builder) {
        try {
            return executeSynchronously(builder.build());
        } catch (RuntimeException exception) {
            return invalidRequest();
        }
    }

    private TransactionResult executeBuiltBankRequest(RequestBuilder builder, boolean mainToBank) {
        try {
            return executeWalletBankSynchronously(builder.build(), mainToBank);
        } catch (RuntimeException exception) {
            return invalidRequest();
        }
    }

    private TransactionResult executeSynchronously(WalletTransactionRequest request) {
        transactionLock.lock();
        try {
            if (!acceptingRequests.get()) {
                return TransactionResult.failure(null, TransactionFailureReason.SERVICE_STOPPING);
            }
            if (!request.type().isGenericWalletMutation()) {
                return invalidRequest();
            }
            ensureParticipants(request);
            TransactionRecord record = store.executeWalletTransaction(request, maximumBalanceMinor);
            publishAfterAcceptance(record);
            return record.isSuccessful()
                    ? TransactionResult.success(record)
                    : TransactionResult.failure(record, record.failureReason());
        } catch (Exception exception) {
            logStorageFailure("commit wallet transaction", exception);
            return TransactionResult.failure(null, TransactionFailureReason.STORAGE_FAILURE);
        } finally {
            transactionLock.unlock();
        }
    }

    private TransactionResult executeWalletBankSynchronously(WalletTransactionRequest request, boolean mainToBank) {
        transactionLock.lock();
        try {
            if (!acceptingRequests.get()) {
                return TransactionResult.failure(null, TransactionFailureReason.SERVICE_STOPPING);
            }
            if (!request.type().isWalletBankMovement()) {
                return invalidRequest();
            }
            ensureParticipants(request);
            TransactionRecord record = store.executeWalletBankTransaction(request, mainToBank, maximumBalanceMinor);
            publishAfterAcceptance(record);
            return record.isSuccessful()
                    ? TransactionResult.success(record)
                    : TransactionResult.failure(record, record.failureReason());
        } catch (Exception exception) {
            logStorageFailure("commit wallet-bank transaction", exception);
            return TransactionResult.failure(null, TransactionFailureReason.STORAGE_FAILURE);
        } finally {
            transactionLock.unlock();
        }
    }

    private void ensureParticipants(WalletTransactionRequest request) throws Exception {
        if (request.sourcePlayerId() != null) {
            store.ensureWalletAccount(request.sourcePlayerId(), startingBalanceMinor, request.timestamp());
        }
        if (request.targetPlayerId() != null) {
            store.ensureWalletAccount(request.targetPlayerId(), startingBalanceMinor, request.timestamp());
        }
    }

    private WalletTransactionRequest buildReversal(TransactionRecord original, TransactionOrigin origin, String reason) {
        String rollbackReason = reason == null || reason.isBlank()
                ? "Rollback of " + original.id()
                : reason;
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("reversed_transaction", original.id().toString());
        metadata.put("reversed_type", original.type().name());
        if (isInternalWalletMovement(original)) {
            metadata.put("internal_wallet_movement", "true");
        }
        return switch (original.operation()) {
            case TRANSFER -> requestForTransferMinor(original.targetPlayerId(), original.sourcePlayerId(),
                    original.appliedAmountMinor(), TransactionType.ROLLBACK, origin,
                    rollbackReason, metadata, original.id(), original.batchId());
            case CREDIT -> requestForDebitMinor(original.targetPlayerId(),
                    original.appliedAmountMinor(), TransactionType.ROLLBACK, origin,
                    rollbackReason, metadata, original.id(), original.batchId());
            case DEBIT -> requestForCreditMinor(original.sourcePlayerId(),
                    original.appliedAmountMinor(), TransactionType.ROLLBACK, origin,
                    rollbackReason, metadata, original.id(), original.batchId());
            case SET -> buildSetReversal(original, origin, rollbackReason, metadata);
        };
    }

    private static boolean isWalletBankTransaction(TransactionRecord record) {
        if (record.type() == TransactionType.WALLET_TO_BANK || record.type() == TransactionType.BANK_TO_WALLET) {
            return true;
        }
        if (record.type() != TransactionType.ROLLBACK) {
            return false;
        }
        String reversedType = record.metadata().get("reversed_type");
        return TransactionType.WALLET_TO_BANK.name().equals(reversedType)
                || TransactionType.BANK_TO_WALLET.name().equals(reversedType);
    }

    private static boolean isInternalWalletMovement(TransactionRecord record) {
        return record.operation() == WalletOperation.TRANSFER || isWalletBankTransaction(record);
    }

    private WalletTransactionRequest buildSetReversal(TransactionRecord original, TransactionOrigin origin,
                                                      String reason, Map<String, String> metadata) {
        if (original.targetPlayerId() == null || original.targetBalanceBeforeMinor() == null
                || original.targetBalanceAfterMinor() == null) {
            throw new IllegalArgumentException("The original set transaction does not contain a reversible balance delta");
        }
        long delta = Math.subtractExact(original.targetBalanceAfterMinor(), original.targetBalanceBeforeMinor());
        if (delta == 0L) {
            throw new IllegalArgumentException("A zero-delta set transaction cannot be rolled back");
        }
        long amountMinor = Math.abs(delta);
        return delta > 0L
                ? requestForDebitMinor(original.targetPlayerId(), amountMinor, TransactionType.ROLLBACK, origin,
                reason, metadata, original.id(), original.batchId())
                : requestForCreditMinor(original.targetPlayerId(), amountMinor, TransactionType.ROLLBACK, origin,
                reason, metadata, original.id(), original.batchId());
    }

    private WalletTransactionRequest requestForCredit(UUID target, double amount, TransactionType type,
                                                       TransactionOrigin origin, String reason, Map<String, String> metadata,
                                                       UUID reversalOf, UUID batchId) {
        return requestForCredit(target, amount, type, origin, reason, metadata, reversalOf, batchId, null);
    }

    private WalletTransactionRequest requestForCredit(UUID target, double amount, TransactionType type,
                                                       TransactionOrigin origin, String reason, Map<String, String> metadata,
                                                       UUID reversalOf, UUID batchId, String idempotencyKey) {
        return requestForCreditMinor(target, positiveMinor(amount), type, origin, reason, metadata, reversalOf, batchId,
                idempotencyKey);
    }

    private WalletTransactionRequest requestForCreditMinor(UUID target, long amountMinor, TransactionType type,
                                                            TransactionOrigin origin, String reason, Map<String, String> metadata,
                                                            UUID reversalOf, UUID batchId) {
        return requestForCreditMinor(target, amountMinor, type, origin, reason, metadata, reversalOf, batchId, null);
    }

    private WalletTransactionRequest requestForCreditMinor(UUID target, long amountMinor, TransactionType type,
                                                            TransactionOrigin origin, String reason, Map<String, String> metadata,
                                                            UUID reversalOf, UUID batchId, String idempotencyKey) {
        return new WalletTransactionRequest(UUID.randomUUID(), now(), requireType(type), requireOrigin(origin),
                WalletOperation.CREDIT, null, requirePlayer(target), requirePositiveMinor(amountMinor), null, reason, metadata,
                reversalOf, batchId, idempotencyKey);
    }

    private WalletTransactionRequest requestForDebit(UUID source, double amount, TransactionType type,
                                                     TransactionOrigin origin, String reason, Map<String, String> metadata,
                                                     UUID reversalOf, UUID batchId) {
        return requestForDebit(source, amount, type, origin, reason, metadata, reversalOf, batchId, null);
    }

    private WalletTransactionRequest requestForDebit(UUID source, double amount, TransactionType type,
                                                     TransactionOrigin origin, String reason, Map<String, String> metadata,
                                                     UUID reversalOf, UUID batchId, String idempotencyKey) {
        return requestForDebitMinor(source, positiveMinor(amount), type, origin, reason, metadata, reversalOf, batchId,
                idempotencyKey);
    }

    private WalletTransactionRequest requestForDebitMinor(UUID source, long amountMinor, TransactionType type,
                                                           TransactionOrigin origin, String reason, Map<String, String> metadata,
                                                           UUID reversalOf, UUID batchId) {
        return requestForDebitMinor(source, amountMinor, type, origin, reason, metadata, reversalOf, batchId, null);
    }

    private WalletTransactionRequest requestForDebitMinor(UUID source, long amountMinor, TransactionType type,
                                                           TransactionOrigin origin, String reason, Map<String, String> metadata,
                                                           UUID reversalOf, UUID batchId, String idempotencyKey) {
        return new WalletTransactionRequest(UUID.randomUUID(), now(), requireType(type), requireOrigin(origin),
                WalletOperation.DEBIT, requirePlayer(source), null, requirePositiveMinor(amountMinor), null, reason, metadata,
                reversalOf, batchId, idempotencyKey);
    }

    private WalletTransactionRequest requestForTransfer(UUID source, UUID target, double amount, TransactionType type,
                                                         TransactionOrigin origin, String reason, Map<String, String> metadata,
                                                         UUID reversalOf, UUID batchId) {
        return requestForTransfer(source, target, amount, type, origin, reason, metadata, reversalOf, batchId, null);
    }

    private WalletTransactionRequest requestForTransfer(UUID source, UUID target, double amount, TransactionType type,
                                                         TransactionOrigin origin, String reason, Map<String, String> metadata,
                                                         UUID reversalOf, UUID batchId, String idempotencyKey) {
        return requestForTransferMinor(source, target, positiveMinor(amount), type, origin, reason, metadata, reversalOf,
                batchId, idempotencyKey);
    }

    private WalletTransactionRequest requestForTransferMinor(UUID source, UUID target, long amountMinor,
                                                              TransactionType type, TransactionOrigin origin, String reason,
                                                              Map<String, String> metadata, UUID reversalOf, UUID batchId) {
        return requestForTransferMinor(source, target, amountMinor, type, origin, reason, metadata, reversalOf, batchId,
                null);
    }

    private WalletTransactionRequest requestForTransferMinor(UUID source, UUID target, long amountMinor,
                                                              TransactionType type, TransactionOrigin origin, String reason,
                                                              Map<String, String> metadata, UUID reversalOf, UUID batchId,
                                                              String idempotencyKey) {
        return new WalletTransactionRequest(UUID.randomUUID(), now(), requireType(type), requireOrigin(origin),
                WalletOperation.TRANSFER, requirePlayer(source), requirePlayer(target), requirePositiveMinor(amountMinor), null,
                reason, metadata, reversalOf, batchId, idempotencyKey);
    }

    private WalletTransactionRequest requestForSet(UUID target, double balance, TransactionType type,
                                                    TransactionOrigin origin, String reason, Map<String, String> metadata,
                                                    UUID reversalOf, UUID batchId) {
        return requestForSet(target, balance, type, origin, reason, metadata, reversalOf, batchId, null);
    }

    private WalletTransactionRequest requestForSet(UUID target, double balance, TransactionType type,
                                                    TransactionOrigin origin, String reason, Map<String, String> metadata,
                                                    UUID reversalOf, UUID batchId, String idempotencyKey) {
        long targetMinor = nonNegativeMinor(balance);
        return new WalletTransactionRequest(UUID.randomUUID(), now(), requireType(type), requireOrigin(origin),
                WalletOperation.SET, null, requirePlayer(target), targetMinor, targetMinor, reason, metadata,
                reversalOf, batchId, idempotencyKey);
    }

    private <T> DurableOperation<T> submitDurable(TransactionSupplier<T> action) {
        DurableOperation<T> operation = new DurableOperation<>();
        if (!acceptingRequests.get()) {
            operation.completeExceptionally(new IllegalStateException("The transaction service is stopping"));
            return operation;
        }
        try {
            executor.execute(() -> {
                if (!operation.tryStart()) return;
                try {
                    operation.complete(action.get());
                } catch (Throwable failure) {
                    operation.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException exception) {
            operation.completeExceptionally(new IllegalStateException("The transaction service is stopping", exception));
        }
        return operation;
    }

    private static <T> DurableOperation<T> completedDurable(T value) {
        DurableOperation<T> operation = new DurableOperation<>();
        if (operation.tryStart()) operation.complete(value);
        return operation;
    }

    private CompletableFuture<TransactionResult> submitResult(TransactionSupplier<TransactionResult> action) {
        if (!acceptingRequests.get()) {
            return CompletableFuture.completedFuture(TransactionResult.failure(null, TransactionFailureReason.SERVICE_STOPPING));
        }
        try {
            return CompletableFuture.supplyAsync(action::get, executor);
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.completedFuture(TransactionResult.failure(null,
                    TransactionFailureReason.SERVICE_STOPPING));
        }
    }

    /**
     * Preserves the public API's durable-completion contract while command-facing synchronous
     * methods may return immediately after a local cache acceptance. The wait itself is
     * non-blocking and runs entirely outside Bukkit threads.
     */
    private CompletableFuture<TransactionResult> submitPersistedResult(TransactionSupplier<TransactionResult> action) {
        return submitResult(action).thenCompose(this::awaitPersistenceIfRequired);
    }

    private CompletableFuture<TransactionResult> awaitPersistenceIfRequired(TransactionResult result) {
        if (result == null || result.transaction() == null || !(store instanceof AsyncWalletTransactionStore asynchronousStore)) {
            return CompletableFuture.completedFuture(result);
        }
        return asynchronousStore.awaitTransactionPersistence(result.transaction().id()).thenApply(ignored -> result);
    }

    private <T> CompletableFuture<T> submitAsync(TransactionSupplier<T> action) {
        if (!acceptingRequests.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("The transaction service is stopping"));
        }
        try {
            return CompletableFuture.supplyAsync(action::get, executor);
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.failedFuture(new IllegalStateException("The transaction service is stopping", exception));
        }
    }

    private Instant now() {
        return clock.instant();
    }

    private static UUID requirePlayer(UUID playerId) {
        return Objects.requireNonNull(playerId, "playerId");
    }

    private static <T> T runInternalServicePath(TransactionSupplier<T> action) {
        try (AutoCloseable ignored = TransactionServiceAccessGuard.enterInternalServicePath()) {
            return action.get();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not leave the internal transaction-service path", exception);
        }
    }

    private static void requireInternalServiceCaller(String operation) {
        TransactionServiceAccessGuard.requireInternalCaller(operation, TransactionService.class);
    }

    private static Storage requireStorage(WalletTransactionStore transactionStore) {
        if (transactionStore instanceof Storage storage) {
            return storage;
        }
        throw new IllegalArgumentException("The transaction store must also provide the SodaEconomy storage contract");
    }

    private static TransactionType requireType(TransactionType type) {
        return Objects.requireNonNull(type, "type");
    }

    private static TransactionOrigin requireOrigin(TransactionOrigin origin) {
        return Objects.requireNonNull(origin, "origin");
    }

    private static long positiveMinor(double amount) {
        if (!Money.isPositive(amount)) {
            throw new IllegalArgumentException("The amount must be positive and finite");
        }
        return Money.toMinorUnits(amount);
    }

    private static long requirePositiveMinor(long amountMinor) {
        if (amountMinor <= 0L) {
            throw new IllegalArgumentException("The amount must be positive");
        }
        return amountMinor;
    }

    private static long nonNegativeMinor(double amount) {
        if (!Money.isValid(amount) || amount < 0D) {
            throw new IllegalArgumentException("The amount must be finite and non-negative");
        }
        return Money.toMinorUnits(amount);
    }

    private static boolean isValidGenericType(TransactionType type, WalletOperation operation) {
        return type != null && type.isGenericWalletMutation() && type.supports(operation);
    }

    private static boolean isApiOrigin(TransactionOrigin origin) {
        return origin != null && origin.type() == TransactionOriginType.API;
    }

    private static boolean isPositiveAmountRepresentable(double amount) {
        try {
            return positiveMinor(amount) > 0L;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isNonNegativeAmountRepresentable(double amount) {
        try {
            return nonNegativeMinor(amount) >= 0L;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static TransactionResult invalidRequest() {
        return TransactionResult.failure(null, TransactionFailureReason.INVALID_REQUEST);
    }

    private static TransactionResult invalidAmount() {
        return TransactionResult.failure(null, TransactionFailureReason.INVALID_AMOUNT);
    }

    private void publishAfterAcceptance(TransactionRecord record) {
        if (record == null || !record.isSuccessful() || !plugin.isEnabled()) {
            return;
        }
        if (store instanceof AsyncWalletTransactionStore asynchronousStore) {
            // Local YAML/SQLite persistence is optimistic. A Bukkit event must not announce a
            // transaction before the backend has confirmed the exact same immutable record.
            asynchronousStore.awaitTransactionPersistence(record.id()).thenRun(() -> publishDurable(record));
            return;
        }
        publishDurable(record);
    }

    private void publishDurable(TransactionRecord record) {
        if (record == null || !record.isSuccessful() || !plugin.isEnabled()) {
            return;
        }
        Runnable publisher = () -> {
            if (plugin.isEnabled()) {
                plugin.getServer().getPluginManager().callEvent(new WalletTransactionEvent(record));
            }
        };
        if (Bukkit.isPrimaryThread()) {
            publisher.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, publisher);
        }
    }

    private void logStorageFailure(String operation, Exception exception) {
        plugin.getLogger().log(Level.WARNING, "[Transactions] Could not " + operation + ".", exception);
    }

    @Override
    public void close() {
        requireInternalServiceCaller("TransactionService#close");
        if (!acceptingRequests.compareAndSet(true, false)) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5L, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        transactionLock.lock();
        try {
            // Wait for every direct synchronous mutation (including async bank interest) before
            // the plugin closes the underlying storage connection.
        } finally {
            transactionLock.unlock();
        }
        if (store instanceof AsyncWalletTransactionStore asynchronousStore) {
            asynchronousStore.close();
        }
    }

    @FunctionalInterface
    private interface RequestBuilder {
        WalletTransactionRequest build();
    }

    @FunctionalInterface
    private interface TransactionSupplier<T> {
        T get();
    }

    private static final class TransactionThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "SodaEconomy-Transactions");
            thread.setDaemon(true);
            return thread;
        }
    }
}
