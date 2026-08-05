package de.sodaeconomy.storage;

/**
 * Result of one coordinated bank-interest attempt.
 *
 * @param executed whether this invocation owned the current interest interval
 * @param changedAccounts number of accounts whose bank balance increased
 */
public record BankInterestResult(boolean executed, int changedAccounts) {
    public BankInterestResult {
        if (changedAccounts < 0) {
            throw new IllegalArgumentException("The number of changed bank accounts must not be negative");
        }
        if (!executed && changedAccounts != 0) {
            throw new IllegalArgumentException("A skipped interest run cannot contain changed accounts");
        }
    }

    public static BankInterestResult executed(int changedAccounts) {
        return new BankInterestResult(true, changedAccounts);
    }

    public static BankInterestResult skipped() {
        return new BankInterestResult(false, 0);
    }
}
