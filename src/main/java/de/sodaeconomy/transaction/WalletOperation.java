package de.sodaeconomy.transaction;

/** Low-level wallet operation used by a transaction request. */
public enum WalletOperation {
    CREDIT,
    DEBIT,
    TRANSFER,
    SET
}
