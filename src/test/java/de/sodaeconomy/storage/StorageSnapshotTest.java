package de.sodaeconomy.storage;

import de.sodaeconomy.transaction.TransactionFailureReason;
import de.sodaeconomy.transaction.TransactionOrigin;
import de.sodaeconomy.transaction.TransactionRecord;
import de.sodaeconomy.transaction.TransactionStatus;
import de.sodaeconomy.transaction.TransactionType;
import de.sodaeconomy.transaction.WalletOperation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StorageSnapshotTest {

    @Test
    void rejectsNonFiniteBalancesBeforeAnyMigrationCanStart() {
        UUID playerId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                () -> new StorageSnapshot(Map.of(playerId, Double.NaN), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new StorageSnapshot(Map.of(), Map.of(playerId, Double.NEGATIVE_INFINITY)));
    }

    @Test
    void rejectsNegativeBalancesBeforeAnyMigrationCanStart() {
        UUID playerId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                () -> new StorageSnapshot(Map.of(playerId, -0.01D), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new StorageSnapshot(Map.of(), Map.of(playerId, -0.01D)));
    }

    @Test
    void normalizesFiniteBalancesInTheImmutableSnapshot() {
        UUID playerId = UUID.randomUUID();

        StorageSnapshot snapshot = new StorageSnapshot(Map.of(playerId, 1.235D), Map.of(playerId, 2.344D));

        assertEquals(Map.of(playerId, 1.24D), snapshot.balances());
        assertEquals(Map.of(playerId, 2.34D), snapshot.bankBalances());
    }

    @Test
    void rejectsJournalConstraintsThatSqlBackendsEnforce() {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID originalTransactionId = UUID.randomUUID();
        TransactionRecord first = successfulTransfer(UUID.randomUUID(), sourceId, targetId,
                "external-request", originalTransactionId);
        TransactionRecord duplicateIdempotencyKey = successfulTransfer(UUID.randomUUID(), sourceId, targetId,
                "external-request", UUID.randomUUID());
        TransactionRecord duplicateSuccessfulReversal = successfulTransfer(UUID.randomUUID(), sourceId, targetId,
                "another-request", originalTransactionId);

        assertThrows(IllegalArgumentException.class,
                () -> new StorageSnapshot(Map.of(), Map.of(), List.of(first, duplicateIdempotencyKey)));
        assertThrows(IllegalArgumentException.class,
                () -> new StorageSnapshot(Map.of(), Map.of(), List.of(first, duplicateSuccessfulReversal)));
    }

    private static TransactionRecord successfulTransfer(UUID id, UUID sourceId, UUID targetId,
                                                        String idempotencyKey, UUID reversalOfTransactionId) {
        return new TransactionRecord(id, Instant.now(), TransactionType.ROLLBACK, TransactionStatus.SUCCESS,
                TransactionOrigin.console(), WalletOperation.TRANSFER, sourceId, targetId,
                100L, 100L, 100L, 0L, 0L, 100L,
                "Snapshot validation", Map.of(), reversalOfTransactionId, null, idempotencyKey,
                TransactionFailureReason.NONE, null);
    }
}
