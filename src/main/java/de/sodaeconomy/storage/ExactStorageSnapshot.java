package de.sodaeconomy.storage;

import de.sodaeconomy.identity.PlayerIdentity;
import de.sodaeconomy.transaction.TransactionRecord;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Internal exact representation transferred between storage backends. */
record ExactStorageSnapshot(Map<UUID, Long> balances, Map<UUID, Long> bankBalances,
                            List<TransactionRecord> walletTransactions,
                            Map<UUID, PlayerIdentity> playerIdentities) {

    ExactStorageSnapshot {
        balances = validatedBalances(balances, "balances");
        bankBalances = validatedBalances(bankBalances, "bankBalances");
        walletTransactions = validatedTransactions(walletTransactions);
        playerIdentities = validatedIdentities(playerIdentities);
    }

    ExactStorageSnapshot(Map<UUID, Long> balances, Map<UUID, Long> bankBalances,
                         List<TransactionRecord> walletTransactions) {
        this(balances, bankBalances, walletTransactions, Map.of());
    }

    static ExactStorageSnapshot empty() {
        return new ExactStorageSnapshot(Map.of(), Map.of(), List.of(), Map.of());
    }

    boolean isEmpty() {
        return balances.isEmpty() && bankBalances.isEmpty() && walletTransactions.isEmpty()
                && playerIdentities.isEmpty();
    }

    private static Map<UUID, Long> validatedBalances(Map<UUID, Long> values, String name) {
        Objects.requireNonNull(values, name);
        Map<UUID, Long> copy = new HashMap<>();
        for (Map.Entry<UUID, Long> entry : values.entrySet()) {
            UUID playerId = Objects.requireNonNull(entry.getKey(), name + " contains a null UUID");
            Long amount = Objects.requireNonNull(entry.getValue(), name + " contains a null balance");
            if (amount < 0L) throw new IllegalArgumentException(name + " contains a negative balance");
            copy.put(playerId, amount);
        }
        return Map.copyOf(copy);
    }

    private static Map<UUID, PlayerIdentity> validatedIdentities(Map<UUID, PlayerIdentity> identities) {
        Objects.requireNonNull(identities, "playerIdentities");
        Map<UUID, PlayerIdentity> copy = new HashMap<>();
        for (Map.Entry<UUID, PlayerIdentity> entry : identities.entrySet()) {
            UUID playerId = Objects.requireNonNull(entry.getKey(), "playerIdentities contains a null UUID");
            PlayerIdentity identity = Objects.requireNonNull(entry.getValue(),
                    "playerIdentities contains a null identity");
            if (!playerId.equals(identity.playerId())) {
                throw new IllegalArgumentException("playerIdentities contains a mismatched UUID");
            }
            copy.put(playerId, identity);
        }
        return Map.copyOf(copy);
    }

    private static List<TransactionRecord> validatedTransactions(List<TransactionRecord> transactions) {
        Objects.requireNonNull(transactions, "walletTransactions");
        HashSet<UUID> identifiers = new HashSet<>();
        HashSet<String> idempotencyKeys = new HashSet<>();
        HashSet<UUID> successfulReversals = new HashSet<>();
        for (TransactionRecord transaction : transactions) {
            TransactionRecord record = Objects.requireNonNull(transaction,
                    "walletTransactions contains a null record");
            if (!identifiers.add(record.id())) {
                throw new IllegalArgumentException("walletTransactions contains a duplicate transaction ID");
            }
            if (record.idempotencyKey() != null && !idempotencyKeys.add(record.idempotencyKey())) {
                throw new IllegalArgumentException("walletTransactions contains a duplicate idempotency key");
            }
            if (record.isSuccessful() && record.reversalOfTransactionId() != null
                    && !successfulReversals.add(record.reversalOfTransactionId())) {
                throw new IllegalArgumentException("walletTransactions contains duplicate successful reversals");
            }
        }
        return List.copyOf(transactions);
    }
}
