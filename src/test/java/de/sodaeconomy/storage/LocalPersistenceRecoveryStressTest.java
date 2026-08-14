package de.sodaeconomy.storage;

import de.sodaeconomy.support.MockBukkitTestBase;
import de.sodaeconomy.transaction.TransactionOrigin;
import de.sodaeconomy.transaction.TransactionRecord;
import de.sodaeconomy.transaction.TransactionRecords;
import de.sodaeconomy.transaction.TransactionType;
import de.sodaeconomy.transaction.WalletOperation;
import de.sodaeconomy.transaction.WalletTransactionRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in filesystem stress test for the bounded local recovery WAL.
 *
 * <p>Run larger release-candidate workloads with for example:</p>
 * <pre>
 * ./gradlew localPersistenceStressTest -Dsodaeconomy.recovery.stress.mutations=100000
 * </pre>
 */
@Tag("recovery-stress")
class LocalPersistenceRecoveryStressTest extends MockBukkitTestBase {

    @Test
    void recoveryGrowthTracksOutstandingMutationsAndReturnsToZeroAfterCatchUp() throws Exception {
        int mutationCount = Integer.getInteger("sodaeconomy.recovery.stress.mutations", 10_000);
        assertTrue(mutationCount >= 1_000, "Stress workloads should exercise at least 1,000 mutations");

        LocalPersistenceRecoveryStore store = new LocalPersistenceRecoveryStore(plugin);
        UUID playerId = UUID.randomUUID();
        List<UUID> writeIds = new ArrayList<>(mutationCount);
        long started = System.nanoTime();

        for (int index = 0; index < mutationCount; index++) {
            UUID writeId = UUID.randomUUID();
            writeIds.add(writeId);
            long before = index;
            long after = before + 1L;
            TransactionRecord record = successfulCredit(playerId, before, after, UUID.randomUUID(),
                    "recovery-stress-" + index, Instant.ofEpochMilli(index + 1L));
            store.appendPending(LocalRecoveryState.of(writeId, record.timestamp(), Map.of(playerId, after),
                    Map.of(), record, Map.of()));
        }

        long appendElapsedNanos = System.nanoTime() - started;
        long peakBytes = store.recoverySizeBytes();
        assertEquals(mutationCount, store.pendingRecoveryCount());
        // This is intentionally a generous structural threshold, not a fragile timing assertion.
        // It proves that the WAL contains compact pending records rather than full historical snapshots.
        assertTrue(peakBytes > 0L && peakBytes < (long) mutationCount * 2_048L,
                "Recovery bytes should remain proportional to compact outstanding mutation records");

        long cleanupStarted = System.nanoTime();
        for (UUID writeId : writeIds) {
            store.markCommitted(writeId);
        }
        long cleanupElapsedNanos = System.nanoTime() - cleanupStarted;

        assertEquals(0, store.pendingRecoveryCount());
        assertEquals(0L, store.recoverySizeBytes());
        assertFalse(java.nio.file.Files.exists(store.recoveryFile()));

        double appendSeconds = appendElapsedNanos / 1_000_000_000.0D;
        double cleanupSeconds = cleanupElapsedNanos / 1_000_000_000.0D;
        double operationsPerSecond = appendSeconds <= 0D ? 0D : mutationCount / appendSeconds;
        System.out.printf(java.util.Locale.ROOT,
                "Recovery WAL stress: mutations=%d peakBytes=%d bytesPerPending=%.2f appendSeconds=%.3f "
                        + "appendOpsPerSecond=%.1f cleanupSeconds=%.3f%n",
                mutationCount, peakBytes, peakBytes / (double) mutationCount, appendSeconds,
                operationsPerSecond, cleanupSeconds);
    }

    private static TransactionRecord successfulCredit(UUID playerId, long before, long after, UUID transactionId,
                                                        String idempotencyKey, Instant timestamp) {
        WalletTransactionRequest request = new WalletTransactionRequest(transactionId, timestamp,
                TransactionType.API_DEPOSIT, TransactionOrigin.api("LocalPersistenceRecoveryStressTest"),
                WalletOperation.CREDIT, null, playerId, after - before, null, "Recovery stress", Map.of(),
                null, null, idempotencyKey);
        return TransactionRecords.successful(request, after - before, null, null, before, after);
    }
}
