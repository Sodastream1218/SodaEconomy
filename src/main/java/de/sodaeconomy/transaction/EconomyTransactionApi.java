package de.sodaeconomy.transaction;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * <p>The supported public integration API for SodaEconomy. Other plugins should obtain this
 * service from Bukkit's {@code ServicesManager}; they must not access storage implementations,
 * managers, or transaction-service implementation classes directly.</p>
 *
 * <p>Implementations must commit the balance change and resulting journal record atomically.
 * Mutation calls require their matching {@code API_*} transaction type and a
 * {@link TransactionOriginType#API} origin, so integrations cannot impersonate player, console,
 * or administrator actions in the audit journal.</p>
 *
 * <p>All methods are asynchronous. Completion callbacks may run away from the Paper main thread;
 * callers must schedule Bukkit or Paper API work back to the appropriate thread.</p>
 */
public interface EconomyTransactionApi {
    CompletableFuture<TransactionResult> deposit(UUID targetPlayerId, double amount, TransactionType type,
                                                  TransactionOrigin origin, String reason, Map<String, String> metadata);

    CompletableFuture<TransactionResult> withdraw(UUID sourcePlayerId, double amount, TransactionType type,
                                                   TransactionOrigin origin, String reason, Map<String, String> metadata);

    CompletableFuture<TransactionResult> transfer(UUID sourcePlayerId, UUID targetPlayerId, double amount,
                                                   TransactionType type, TransactionOrigin origin, String reason,
                                                   Map<String, String> metadata);

    CompletableFuture<TransactionResult> setBalance(UUID targetPlayerId, double targetBalance, TransactionType type,
                                                      TransactionOrigin origin, String reason, Map<String, String> metadata);

    /**
     * Reads the persisted wallet balance without creating an account or awarding a starting balance.
     * Implementations return {@code 0.0} when no stored wallet account exists. The default keeps
     * existing third-party API implementations binary and source compatible until they support
     * the read operation.
     *
     * @param playerId player account to read
     * @return an asynchronous persisted wallet balance
     */
    default CompletableFuture<Double> getStoredBalance(UUID playerId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "Stored wallet balance reads are not supported by this EconomyTransactionApi implementation"));
    }

    /**
     * Convenience API for an external wallet credit. It always uses
     * {@link TransactionType#API_DEPOSIT}; integrations cannot select an administrator or player
     * transaction type through this overload.
     *
     * <p>An implementation that supports idempotency must override this method. The compatibility
     * default rejects a non-null idempotency key rather than silently discarding it.</p>
     *
     * @param targetPlayerId recipient account
     * @param amount amount to credit
     * @param origin API origin, normally {@link TransactionOrigin#api(String)}
     * @param options immutable reason, metadata, and optional idempotency key
     * @return the asynchronous transaction result
     */
    default CompletableFuture<TransactionResult> deposit(UUID targetPlayerId, double amount, TransactionOrigin origin,
                                                          TransactionRequestOptions options) {
        TransactionRequestOptions requestOptions = requireOptions(options);
        if (requestOptions.idempotencyKey() != null) {
            return unsupportedIdempotency();
        }
        return deposit(targetPlayerId, amount, TransactionType.API_DEPOSIT, origin, requestOptions.reason(),
                requestOptions.metadata());
    }

    /**
     * Convenience API for an external wallet debit. It always uses
     * {@link TransactionType#API_WITHDRAW}. See {@link #deposit(UUID, double, TransactionOrigin,
     * TransactionRequestOptions)} for idempotency compatibility behaviour.
     */
    default CompletableFuture<TransactionResult> withdraw(UUID sourcePlayerId, double amount, TransactionOrigin origin,
                                                           TransactionRequestOptions options) {
        TransactionRequestOptions requestOptions = requireOptions(options);
        if (requestOptions.idempotencyKey() != null) {
            return unsupportedIdempotency();
        }
        return withdraw(sourcePlayerId, amount, TransactionType.API_WITHDRAW, origin, requestOptions.reason(),
                requestOptions.metadata());
    }

    /**
     * Convenience API for an external wallet transfer. It always uses
     * {@link TransactionType#API_TRANSFER}. See {@link #deposit(UUID, double, TransactionOrigin,
     * TransactionRequestOptions)} for idempotency compatibility behaviour.
     */
    default CompletableFuture<TransactionResult> transfer(UUID sourcePlayerId, UUID targetPlayerId, double amount,
                                                           TransactionOrigin origin, TransactionRequestOptions options) {
        TransactionRequestOptions requestOptions = requireOptions(options);
        if (requestOptions.idempotencyKey() != null) {
            return unsupportedIdempotency();
        }
        return transfer(sourcePlayerId, targetPlayerId, amount, TransactionType.API_TRANSFER, origin,
                requestOptions.reason(), requestOptions.metadata());
    }

    /**
     * Convenience API for an external absolute wallet balance update. It always uses
     * {@link TransactionType#API_SET}. See {@link #deposit(UUID, double, TransactionOrigin,
     * TransactionRequestOptions)} for idempotency compatibility behaviour.
     */
    default CompletableFuture<TransactionResult> setBalance(UUID targetPlayerId, double targetBalance,
                                                             TransactionOrigin origin, TransactionRequestOptions options) {
        TransactionRequestOptions requestOptions = requireOptions(options);
        if (requestOptions.idempotencyKey() != null) {
            return unsupportedIdempotency();
        }
        return setBalance(targetPlayerId, targetBalance, TransactionType.API_SET, origin, requestOptions.reason(),
                requestOptions.metadata());
    }

    CompletableFuture<TransactionResult> rollback(UUID transactionId, TransactionOrigin origin, String reason);

    CompletableFuture<TransactionPage> findTransactions(TransactionQuery query);

    CompletableFuture<EconomyStatistics> getStatistics();

    /**
     * Returns extended server analytics when supported by the active implementation. A default is
     * retained for binary and source compatibility with integrations that implemented this API
     * before extended analytics were introduced.
     */
    default CompletableFuture<EconomyAnalytics> getAnalytics() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Extended analytics are not supported"));
    }

    /**
     * Returns a participant's transaction aggregate when supported by the active implementation.
     * Built-in SodaEconomy services always provide this operation.
     */
    default CompletableFuture<PlayerTransactionStatistics> getPlayerStatistics(UUID playerId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Player analytics are not supported"));
    }

    private static TransactionRequestOptions requireOptions(TransactionRequestOptions options) {
        return Objects.requireNonNull(options, "options");
    }

    private static CompletableFuture<TransactionResult> unsupportedIdempotency() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "Idempotency keys require an EconomyTransactionApi implementation with idempotent request support"));
    }
}
