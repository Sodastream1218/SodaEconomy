package de.sodaeconomy.integration.vault;

import de.sodaeconomy.transaction.DurableOperation;
import de.sodaeconomy.transaction.TransactionOrigin;
import de.sodaeconomy.transaction.TransactionResult;
import de.sodaeconomy.transaction.TransactionService;
import de.sodaeconomy.transaction.WalletAccountLookup;
import de.sodaeconomy.transaction.WalletAccountState;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Production gateway. Every mutation remains inside the central TransactionService. */
final class TransactionServiceVaultGateway implements VaultTransactionGateway {
    private static final TransactionOrigin VAULT_ORIGIN = TransactionOrigin.api("Vault");
    private final TransactionService transactions;

    TransactionServiceVaultGateway(TransactionService transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public DurableOperation<TransactionResult> deposit(UUID playerId, double amount, Map<String, String> metadata,
                                                        Duration localAdmissionTimeout) {
        return transactions.depositForIntegration(playerId, amount, VAULT_ORIGIN, "Vault deposit", metadata,
                localAdmissionTimeout);
    }

    @Override
    public DurableOperation<TransactionResult> withdraw(UUID playerId, double amount, Map<String, String> metadata,
                                                         Duration localAdmissionTimeout) {
        return transactions.withdrawForIntegration(playerId, amount, VAULT_ORIGIN, "Vault withdrawal", metadata,
                localAdmissionTimeout);
    }

    @Override
    public CompletableFuture<WalletAccountLookup> lookup(UUID playerId) {
        return transactions.lookupAccountForIntegration(playerId);
    }

    @Override
    public DurableOperation<WalletAccountState> createAccount(UUID playerId, Duration localAdmissionTimeout) {
        return transactions.createAccountForIntegration(playerId, localAdmissionTimeout);
    }
}
