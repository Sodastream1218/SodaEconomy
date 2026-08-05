package de.sodaeconomy.transaction;

import java.util.List;
import java.util.Objects;

/**
 * Extended, immutable server-wide wallet analytics. It complements the stable
 * {@link EconomyStatistics} API without changing that record's constructor contract.
 */
public record EconomyAnalytics(EconomyStatistics statistics, long largestTransactionMinor,
                               long totalTransferVolumeMinor) {

    public EconomyAnalytics {
        statistics = Objects.requireNonNull(statistics, "statistics");
        if (largestTransactionMinor < 0L || totalTransferVolumeMinor < 0L) {
            throw new IllegalArgumentException("Economy analytics must not contain negative values");
        }
    }

    /** Builds analytics from an already calculated aggregate and its immutable transaction journal. */
    public static EconomyAnalytics from(EconomyStatistics statistics, List<TransactionRecord> records) {
        Objects.requireNonNull(records, "records");
        long largestTransaction = 0L;
        long transferVolume = 0L;
        for (TransactionRecord record : records) {
            if (!record.isSuccessful()) {
                continue;
            }
            largestTransaction = Math.max(largestTransaction, record.appliedAmountMinor());
            transferVolume = Math.addExact(transferVolume, TransactionRecords.transferVolume(record));
        }
        return new EconomyAnalytics(statistics, largestTransaction, transferVolume);
    }
}
