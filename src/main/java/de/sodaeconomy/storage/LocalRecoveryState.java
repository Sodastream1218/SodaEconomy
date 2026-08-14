package de.sodaeconomy.storage;

import de.sodaeconomy.transaction.TransactionRecord;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One durable local-recovery record for a write accepted by the asynchronous single-server queue.
 *
 * <p>The record deliberately stores only absolute post-write state for accounts touched by this
 * write plus the immutable wallet journal record, when one exists. It is not a historical ledger;
 * long-term transaction history remains owned by the authoritative YAML/SQLite backend.</p>
 */
record LocalRecoveryState(
        UUID writeId,
        Instant enqueuedAt,
        Map<UUID, Long> walletBalances,
        Map<UUID, Long> bankBalances,
        TransactionRecord walletTransaction,
        Map<UUID, AccountInitialization> accountInitializations
) {
    LocalRecoveryState {
        writeId = Objects.requireNonNull(writeId, "writeId");
        enqueuedAt = Objects.requireNonNull(enqueuedAt, "enqueuedAt");
        walletBalances = immutableBalances(walletBalances, "walletBalances");
        bankBalances = immutableBalances(bankBalances, "bankBalances");
        accountInitializations = immutableInitializations(accountInitializations);
    }

    static LocalRecoveryState of(UUID writeId, Instant enqueuedAt,
                                 Map<UUID, Long> walletBalances,
                                 Map<UUID, Long> bankBalances,
                                 TransactionRecord walletTransaction,
                                 Map<UUID, AccountInitialization> accountInitializations) {
        return new LocalRecoveryState(writeId, enqueuedAt, walletBalances, bankBalances,
                walletTransaction, accountInitializations);
    }

    private static Map<UUID, Long> immutableBalances(Map<UUID, Long> values, String name) {
        Objects.requireNonNull(values, name);
        Map<UUID, Long> copy = new LinkedHashMap<>();
        for (Map.Entry<UUID, Long> entry : values.entrySet()) {
            UUID playerId = Objects.requireNonNull(entry.getKey(), name + " contains a null player ID");
            Long amount = Objects.requireNonNull(entry.getValue(), name + " contains a null balance");
            if (amount < 0L) {
                throw new IllegalArgumentException(name + " contains a negative balance");
            }
            copy.put(playerId, amount);
        }
        return Map.copyOf(copy);
    }

    private static Map<UUID, AccountInitialization> immutableInitializations(
            Map<UUID, AccountInitialization> values) {
        Objects.requireNonNull(values, "accountInitializations");
        Map<UUID, AccountInitialization> copy = new LinkedHashMap<>();
        for (Map.Entry<UUID, AccountInitialization> entry : values.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "account initialization contains a null player ID"),
                    Objects.requireNonNull(entry.getValue(), "account initialization must not be null"));
        }
        return Map.copyOf(copy);
    }

    record AccountInitialization(long startingBalanceMinor, Instant timestamp) {
        AccountInitialization {
            if (startingBalanceMinor < 0L) {
                throw new IllegalArgumentException("The recovery starting balance must not be negative");
            }
            timestamp = Objects.requireNonNull(timestamp, "timestamp");
        }
    }
}
