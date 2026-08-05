package de.sodaeconomy.integration.vault;

import de.sodaeconomy.transaction.DurableOperation;
import de.sodaeconomy.transaction.TransactionResult;
import de.sodaeconomy.transaction.WalletAccountLookup;
import de.sodaeconomy.transaction.WalletAccountState;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Small testable boundary from the Vault facade to SodaEconomy's transaction service. */
interface VaultTransactionGateway {
    DurableOperation<TransactionResult> deposit(UUID playerId, double amount, Map<String, String> metadata,
                                                 Duration localAdmissionTimeout);
    DurableOperation<TransactionResult> withdraw(UUID playerId, double amount, Map<String, String> metadata,
                                                  Duration localAdmissionTimeout);
    CompletableFuture<WalletAccountLookup> lookup(UUID playerId);
    DurableOperation<WalletAccountState> createAccount(UUID playerId, Duration localAdmissionTimeout);
}
