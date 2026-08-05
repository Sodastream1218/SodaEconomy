package de.sodaeconomy.transaction;

/** Exact account-existence result used by synchronous compatibility adapters such as Vault. */
public record WalletAccountLookup(boolean exists, long balanceMinor) {
    public WalletAccountLookup {
        if (balanceMinor < 0L) throw new IllegalArgumentException("Wallet balance must not be negative");
        if (!exists && balanceMinor != 0L) throw new IllegalArgumentException("A missing account cannot have a balance");
    }
}
