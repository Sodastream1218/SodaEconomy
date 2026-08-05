package de.sodaeconomy.storage;

import de.sodaeconomy.transaction.EconomyStatistics;
import de.sodaeconomy.transaction.EconomyAnalytics;
import de.sodaeconomy.transaction.PlayerTransactionStatistics;
import de.sodaeconomy.transaction.TransactionPage;
import de.sodaeconomy.transaction.TransactionQuery;
import de.sodaeconomy.transaction.TransactionRecord;
import de.sodaeconomy.transaction.WalletAccountState;
import de.sodaeconomy.transaction.WalletTransactionRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Backend-specific atomic boundary for a wallet balance change and its immutable journal entry.
 * Implementations must never commit either part without the other.
 */
public interface WalletTransactionStore {
    WalletAccountState ensureWalletAccount(UUID playerId, long startingBalanceMinor, Instant timestamp) throws Exception;

    TransactionRecord executeWalletTransaction(WalletTransactionRequest request, long maximumBalanceMinor) throws Exception;

    /**
     * Atomically moves funds between one player's wallet and bank account while appending the
     * wallet-side journal record. A {@code mainToBank} request must use a debit wallet operation;
     * a bank-to-wallet request must use a credit wallet operation. The bank-side history is
     * intentionally not modeled yet, but neither balance may be changed without its wallet audit
     * record.
     */
    TransactionRecord executeWalletBankTransaction(WalletTransactionRequest request, boolean mainToBank,
                                                   long maximumBalanceMinor) throws Exception;

    /** Returns an immutable journal record, or an empty result for an unknown or null ID. */
    Optional<TransactionRecord> findTransaction(UUID transactionId) throws Exception;

    TransactionPage findTransactions(TransactionQuery query) throws Exception;

    EconomyStatistics getEconomyStatistics() throws Exception;

    /**
     * Returns extended server-wide analytics. Concrete stores should override this method so the
     * existing statistics scan can also calculate the additional values without a second query.
     */
    default EconomyAnalytics getEconomyAnalytics() throws Exception {
        return EconomyAnalytics.from(getEconomyStatistics(), getAllWalletTransactions());
    }

    /**
     * Returns one participant's audit aggregate. Concrete database stores override this with a
     * participant-filtered query; the default retains compatibility for third-party stores.
     */
    default PlayerTransactionStatistics getPlayerTransactionStatistics(UUID playerId) throws Exception {
        return PlayerTransactionStatistics.fromRecords(playerId, getAllWalletTransactions());
    }

    /**
     * Returns the complete immutable wallet journal in deterministic chronological order
     * (oldest first, then transaction ID).
     */
    List<TransactionRecord> getAllWalletTransactions() throws Exception;

    /**
     * Returns whether a successful reversal already exists for the supplied transaction. Storage
     * implementations may override this with an indexed lookup; the default preserves source
     * compatibility for existing implementations.
     */
    default boolean hasSuccessfulRollback(UUID originalTransactionId) throws Exception {
        if (originalTransactionId == null) {
            return false;
        }
        return getAllWalletTransactions().stream().anyMatch(record -> record.isSuccessful()
                && originalTransactionId.equals(record.reversalOfTransactionId()));
    }
}
