package de.sodaeconomy.transaction;

/** Machine-readable reason for a transaction that was not committed successfully. */
public enum TransactionFailureReason {
    NONE,
    INVALID_REQUEST,
    INVALID_AMOUNT,
    INSUFFICIENT_FUNDS,
    MAXIMUM_BALANCE_EXCEEDED,
    ACCOUNT_NOT_FOUND,
    DUPLICATE_ROLLBACK,
    NOT_REVERSIBLE,
    CONFLICT,
    STORAGE_FAILURE,
    SERVICE_STOPPING
}
