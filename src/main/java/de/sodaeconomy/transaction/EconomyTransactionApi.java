package de.sodaeconomy.transaction;

import de.sodaeconomy.Money;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongFunction;

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
 *
 * <p>The legacy {@code double} mutation methods remain supported for compatibility. New integrations
 * that require exact decimal semantics should prefer the {@code *Minor} methods or the
 * {@link BigDecimal} convenience overloads. SodaEconomy's built-in implementation keeps those values
 * in canonical minor units without round-tripping through binary floating point.</p>
 *
 * <p>Read operations distinguish valid empty/zero results from backend failures. A missing stored
 * wallet may legitimately resolve to {@code 0.0}, and an economy with no accounts may legitimately
 * return an empty snapshot. Persistent read failures instead complete the returned future
 * exceptionally; callers must not interpret them as real economy data.</p>
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
     * Exact wallet credit using canonical minor units (for example, cents when the scale is two).
     * SodaEconomy's implementation overrides this method and never converts the amount through
     * {@code double}. The compatibility default only delegates to a legacy implementation when the
     * value can round-trip through that implementation without losing a minor unit.
     */
    default CompletableFuture<TransactionResult> depositMinor(UUID targetPlayerId, long amountMinor,
                                                               TransactionOrigin origin,
                                                               TransactionRequestOptions options) {
        if (amountMinor <= 0L) return invalidAmountResult();
        try {
            return deposit(targetPlayerId, exactLegacyDouble(amountMinor), origin, requireOptions(options));
        } catch (UnsupportedOperationException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    /** Exact wallet debit counterpart to {@link #depositMinor(UUID, long, TransactionOrigin, TransactionRequestOptions)}. */
    default CompletableFuture<TransactionResult> withdrawMinor(UUID sourcePlayerId, long amountMinor,
                                                                TransactionOrigin origin,
                                                                TransactionRequestOptions options) {
        if (amountMinor <= 0L) return invalidAmountResult();
        try {
            return withdraw(sourcePlayerId, exactLegacyDouble(amountMinor), origin, requireOptions(options));
        } catch (UnsupportedOperationException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    /** Exact wallet transfer counterpart using canonical minor units. */
    default CompletableFuture<TransactionResult> transferMinor(UUID sourcePlayerId, UUID targetPlayerId,
                                                                long amountMinor, TransactionOrigin origin,
                                                                TransactionRequestOptions options) {
        if (amountMinor <= 0L) return invalidAmountResult();
        try {
            return transfer(sourcePlayerId, targetPlayerId, exactLegacyDouble(amountMinor), origin,
                    requireOptions(options));
        } catch (UnsupportedOperationException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    /** Exact absolute wallet balance update using canonical minor units. */
    default CompletableFuture<TransactionResult> setBalanceMinor(UUID targetPlayerId, long targetBalanceMinor,
                                                                  TransactionOrigin origin,
                                                                  TransactionRequestOptions options) {
        if (targetBalanceMinor < 0L) return invalidAmountResult();
        try {
            return setBalance(targetPlayerId, exactLegacyDouble(targetBalanceMinor), origin, requireOptions(options));
        } catch (UnsupportedOperationException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    /** Decimal wallet credit converted once to SodaEconomy's canonical two-decimal minor units. */
    default CompletableFuture<TransactionResult> deposit(UUID targetPlayerId, BigDecimal amount,
                                                          TransactionOrigin origin, TransactionRequestOptions options) {
        TransactionRequestOptions requestOptions = requireOptions(options);
        return decimalMutation(amount, minor -> depositMinor(targetPlayerId, minor, origin, requestOptions));
    }

    /** Decimal wallet debit converted once to SodaEconomy's canonical two-decimal minor units. */
    default CompletableFuture<TransactionResult> withdraw(UUID sourcePlayerId, BigDecimal amount,
                                                           TransactionOrigin origin, TransactionRequestOptions options) {
        TransactionRequestOptions requestOptions = requireOptions(options);
        return decimalMutation(amount, minor -> withdrawMinor(sourcePlayerId, minor, origin, requestOptions));
    }

    /** Decimal wallet transfer converted once to SodaEconomy's canonical two-decimal minor units. */
    default CompletableFuture<TransactionResult> transfer(UUID sourcePlayerId, UUID targetPlayerId, BigDecimal amount,
                                                           TransactionOrigin origin, TransactionRequestOptions options) {
        TransactionRequestOptions requestOptions = requireOptions(options);
        return decimalMutation(amount,
                minor -> transferMinor(sourcePlayerId, targetPlayerId, minor, origin, requestOptions));
    }

    /** Decimal absolute wallet balance converted once to SodaEconomy's canonical two-decimal minor units. */
    default CompletableFuture<TransactionResult> setBalance(UUID targetPlayerId, BigDecimal targetBalance,
                                                             TransactionOrigin origin, TransactionRequestOptions options) {
        Objects.requireNonNull(targetBalance, "targetBalance");
        final long targetMinor;
        try {
            targetMinor = Money.toMinorUnits(targetBalance);
        } catch (IllegalArgumentException exception) {
            return invalidAmountResult();
        }
        return setBalanceMinor(targetPlayerId, targetMinor, origin, requireOptions(options));
    }

    /**
     * Reads the persisted wallet balance without creating an account or awarding a starting balance.
     * Implementations return {@code 0.0} when no stored wallet account exists. The default keeps
     * existing third-party API implementations binary and source compatible until they support
     * the read operation.
     *
     * @param playerId player account to read
     * @return an asynchronous persisted wallet balance; persistent read failures complete exceptionally
     */
    default CompletableFuture<Double> getStoredBalance(UUID playerId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "Stored wallet balance reads are not supported by this EconomyTransactionApi implementation"));
    }

    /**
     * Returns an exact, immutable snapshot of stored wallet balances in minor units. This is a
     * read-only presentation/integration surface and must never create accounts. Implementations
     * should perform persistent I/O asynchronously. Persistent read failures must complete the future
     * exceptionally rather than returning an empty snapshot.
     */
    default CompletableFuture<Map<UUID, Long>> getStoredBalancesMinorUnits() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "Exact stored wallet snapshots are not supported by this EconomyTransactionApi implementation"));
    }

    /**
     * Returns an exact, immutable snapshot of stored bank balances in minor units. Missing bank
     * accounts are represented by absence from the returned map. Implementations should perform
     * persistent I/O asynchronously. Persistent read failures must complete the future exceptionally
     * rather than returning an empty snapshot.
     */
    default CompletableFuture<Map<UUID, Long>> getStoredBankBalancesMinorUnits() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "Exact stored bank snapshots are not supported by this EconomyTransactionApi implementation"));
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

    private static CompletableFuture<TransactionResult> decimalMutation(BigDecimal amount,
            LongFunction<CompletableFuture<TransactionResult>> mutation) {
        Objects.requireNonNull(amount, "amount");
        final long amountMinor;
        try {
            amountMinor = Money.toMinorUnits(amount);
        } catch (IllegalArgumentException exception) {
            return invalidAmountResult();
        }
        if (amountMinor <= 0L) return invalidAmountResult();
        return mutation.apply(amountMinor);
    }

    private static double exactLegacyDouble(long amountMinor) {
        double legacyAmount = Money.fromMinorUnits(amountMinor);
        try {
            if (Money.toMinorUnits(legacyAmount) == amountMinor) return legacyAmount;
        } catch (IllegalArgumentException ignored) {
            // Fall through to the explicit compatibility error below.
        }
        throw new UnsupportedOperationException(
                "This EconomyTransactionApi implementation only exposes double mutations and cannot represent "
                        + amountMinor + " minor units exactly. Implement the exact minor-unit mutation methods.");
    }

    private static CompletableFuture<TransactionResult> invalidAmountResult() {
        return CompletableFuture.completedFuture(TransactionResult.failure(null,
                TransactionFailureReason.INVALID_AMOUNT));
    }

    private static TransactionRequestOptions requireOptions(TransactionRequestOptions options) {
        return Objects.requireNonNull(options, "options");
    }

    private static CompletableFuture<TransactionResult> unsupportedIdempotency() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "Idempotency keys require an EconomyTransactionApi implementation with idempotent request support"));
    }
}
