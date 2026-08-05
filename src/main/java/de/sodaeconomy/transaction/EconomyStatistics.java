package de.sodaeconomy.transaction;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable aggregate view of the current wallet economy and immutable wallet journal. Credit and
 * debit totals represent money introduced into and removed from wallet circulation; transfers and
 * wallet-bank moves are excluded because they only relocate existing funds.
 */
public record EconomyStatistics(
        long walletAccountCount,
        long totalBalanceMinor,
        UUID richestPlayerId,
        long richestBalanceMinor,
        long totalTransactionCount,
        long successfulTransactionCount,
        long failedTransactionCount,
        long cancelledTransactionCount,
        long totalCreditsMinor,
        long totalDebitsMinor
) {
    public EconomyStatistics {
        if (walletAccountCount < 0 || totalBalanceMinor < 0 || richestBalanceMinor < 0
                || totalTransactionCount < 0 || successfulTransactionCount < 0
                || failedTransactionCount < 0 || cancelledTransactionCount < 0
                || totalCreditsMinor < 0 || totalDebitsMinor < 0) {
            throw new IllegalArgumentException("Economy statistics must not contain negative values");
        }
        if (successfulTransactionCount + failedTransactionCount + cancelledTransactionCount > totalTransactionCount) {
            throw new IllegalArgumentException("Transaction status counts exceed the total count");
        }
        if (walletAccountCount == 0 && richestPlayerId != null) {
            throw new IllegalArgumentException("An empty economy cannot have a richest player");
        }
    }

    public long averageBalanceMinor() {
        return walletAccountCount == 0 ? 0 : totalBalanceMinor / walletAccountCount;
    }
}
