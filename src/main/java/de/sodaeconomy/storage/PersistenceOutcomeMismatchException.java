package de.sodaeconomy.storage;

import de.sodaeconomy.transaction.TransactionRecord;

import java.util.Objects;

/**
 * Indicates that the delegate committed a different journal outcome than the local optimistic
 * evaluation. Local backends should never produce this result; the queue pauses instead of
 * continuing from an unverified state.
 */
public final class PersistenceOutcomeMismatchException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    private final transient TransactionRecord expected;
    private final transient TransactionRecord actual;

    public PersistenceOutcomeMismatchException(TransactionRecord expected, TransactionRecord actual) {
        super("The persisted transaction outcome did not match the accepted local outcome");
        this.expected = Objects.requireNonNull(expected, "expected");
        this.actual = Objects.requireNonNull(actual, "actual");
    }

    public TransactionRecord expected() {
        return expected;
    }

    public TransactionRecord actual() {
        return actual;
    }
}
