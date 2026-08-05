package de.sodaeconomy.transaction;

/** The immutable outcome recorded for a wallet transaction attempt. */
public enum TransactionStatus {
    SUCCESS,
    FAILED,
    CANCELLED
}
