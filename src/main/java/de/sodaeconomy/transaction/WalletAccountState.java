package de.sodaeconomy.transaction;

/** Current persisted state returned after an account is atomically created or read. */
public record WalletAccountState(long balanceMinor, boolean created) {
    public WalletAccountState {
        if (balanceMinor < 0) {
            throw new IllegalArgumentException("Wallet balances must not be negative");
        }
    }
}
