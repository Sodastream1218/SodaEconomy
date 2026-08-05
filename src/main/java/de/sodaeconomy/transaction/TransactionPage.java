package de.sodaeconomy.transaction;

import java.util.List;
import java.util.Objects;

/** One immutable page of transaction-history results ordered by timestamp and identifier. */
public record TransactionPage(List<TransactionRecord> records, int offset, int limit, boolean hasMore) {
    public TransactionPage {
        records = List.copyOf(Objects.requireNonNull(records, "records"));
        if (offset < 0 || limit < 1) {
            throw new IllegalArgumentException("Invalid transaction page bounds");
        }
    }
}
