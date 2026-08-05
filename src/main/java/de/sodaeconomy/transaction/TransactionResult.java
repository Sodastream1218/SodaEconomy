package de.sodaeconomy.transaction;

import java.util.Objects;

/** Result returned by the public transaction API and internal transaction service. */
public record TransactionResult(TransactionRecord transaction, TransactionFailureReason failureReason) {
    public TransactionResult {
        failureReason = Objects.requireNonNullElse(failureReason, TransactionFailureReason.NONE);
        if (transaction != null && transaction.isSuccessful() && failureReason != TransactionFailureReason.NONE) {
            throw new IllegalArgumentException("A successful transaction cannot have a failure reason");
        }
    }

    public boolean isSuccessful() {
        return transaction != null && transaction.isSuccessful();
    }

    public static TransactionResult success(TransactionRecord transaction) {
        if (transaction == null || !transaction.isSuccessful()) {
            throw new IllegalArgumentException("A successful result requires a successful transaction record");
        }
        return new TransactionResult(transaction, TransactionFailureReason.NONE);
    }

    public static TransactionResult failure(TransactionRecord transaction, TransactionFailureReason reason) {
        if (reason == null || reason == TransactionFailureReason.NONE) {
            throw new IllegalArgumentException("A failed result requires a failure reason");
        }
        return new TransactionResult(transaction, reason);
    }
}
