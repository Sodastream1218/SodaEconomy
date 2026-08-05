package de.sodaeconomy.transaction;

/**
 * Thrown when external code calls a concrete {@link TransactionService} method that is retained
 * only for SodaEconomy's own commands, tasks, and legacy compatibility layers.
 */
public final class UnsupportedTransactionServiceAccessException extends RuntimeException {
    public UnsupportedTransactionServiceAccessException(String message) {
        super(message);
    }
}
