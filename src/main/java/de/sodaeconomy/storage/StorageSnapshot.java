package de.sodaeconomy.storage;

import de.sodaeconomy.Money;
import de.sodaeconomy.transaction.TransactionRecord;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable, validated view of all data transferred during a storage migration. */
public record StorageSnapshot(Map<UUID, Double> balances, Map<UUID, Double> bankBalances,
                              List<TransactionRecord> walletTransactions) {

    public StorageSnapshot {
        balances = validatedCopy(balances, "balances");
        bankBalances = validatedCopy(bankBalances, "bankBalances");
        walletTransactions = validatedTransactions(walletTransactions);
    }

    /** Retains source compatibility for callers that migrate the legacy balance-only state. */
    public StorageSnapshot(Map<UUID, Double> balances, Map<UUID, Double> bankBalances) {
        this(balances, bankBalances, List.of());
    }

    public static StorageSnapshot empty() {
        return new StorageSnapshot(Map.of(), Map.of(), List.of());
    }

    public boolean isEmpty() {
        return balances.isEmpty() && bankBalances.isEmpty() && walletTransactions.isEmpty();
    }

    private static Map<UUID, Double> validatedCopy(Map<UUID, Double> values, String name) {
        Objects.requireNonNull(values, name);
        Map<UUID, Double> copy = new HashMap<>();
        for (Map.Entry<UUID, Double> entry : values.entrySet()) {
            UUID playerId = Objects.requireNonNull(entry.getKey(), name + " contains a null UUID");
            Double amount = Objects.requireNonNull(entry.getValue(), name + " contains a null balance");
            if (!Money.isValid(amount) || amount < 0.0D) {
                throw new IllegalArgumentException(name + " contains an invalid balance");
            }
            copy.put(playerId, Money.normalize(amount));
        }
        return Map.copyOf(copy);
    }

    private static List<TransactionRecord> validatedTransactions(List<TransactionRecord> transactions) {
        Objects.requireNonNull(transactions, "walletTransactions");
        HashSet<UUID> identifiers = new HashSet<>();
        HashSet<String> idempotencyKeys = new HashSet<>();
        HashSet<UUID> successfulReversals = new HashSet<>();
        for (TransactionRecord transaction : transactions) {
            TransactionRecord record = Objects.requireNonNull(transaction, "walletTransactions contains a null record");
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
