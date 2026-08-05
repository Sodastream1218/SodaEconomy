package de.sodaeconomy.transaction;

/** Describes the business operation represented by a wallet journal record. */
public enum TransactionType {
    INITIAL_BALANCE,
    LEGACY_OPENING_BALANCE,
    PLAYER_TRANSFER,
    ADMIN_GIVE,
    ADMIN_TAKE,
    ADMIN_SET,
    ADMIN_RESET,
    API_DEPOSIT,
    API_WITHDRAW,
    API_TRANSFER,
    API_SET,
    ROLLBACK,
    WALLET_TO_BANK,
    BANK_TO_WALLET;

    /**
     * Returns whether this audit type can accurately describe the supplied low-level operation.
     * Bootstrap records are created directly by storage initialization and can never be submitted
     * as a normal transaction request.
     */
    public boolean supports(WalletOperation operation) {
        return switch (this) {
            case INITIAL_BALANCE, LEGACY_OPENING_BALANCE -> false;
            case ADMIN_GIVE, API_DEPOSIT, BANK_TO_WALLET -> operation == WalletOperation.CREDIT;
            case ADMIN_TAKE, API_WITHDRAW, WALLET_TO_BANK -> operation == WalletOperation.DEBIT;
            case PLAYER_TRANSFER, API_TRANSFER -> operation == WalletOperation.TRANSFER;
            case ADMIN_SET, ADMIN_RESET, API_SET -> operation == WalletOperation.SET;
            case ROLLBACK -> true;
        };
    }

    /** Returns whether the type represents a storage-generated opening record. */
    public boolean isBootstrapRecord() {
        return this == INITIAL_BALANCE || this == LEGACY_OPENING_BALANCE;
    }

    /** Returns whether the transaction must use the wallet-bank atomic storage boundary. */
    public boolean isWalletBankMovement() {
        return this == WALLET_TO_BANK || this == BANK_TO_WALLET;
    }

    /** Returns whether this type may be committed through the normal wallet mutation path. */
    public boolean isGenericWalletMutation() {
        return switch (this) {
            case PLAYER_TRANSFER, ADMIN_GIVE, ADMIN_TAKE, ADMIN_SET, ADMIN_RESET,
                    API_DEPOSIT, API_WITHDRAW, API_TRANSFER, API_SET -> true;
            case INITIAL_BALANCE, LEGACY_OPENING_BALANCE, ROLLBACK,
                    WALLET_TO_BANK, BANK_TO_WALLET -> false;
        };
    }
}
