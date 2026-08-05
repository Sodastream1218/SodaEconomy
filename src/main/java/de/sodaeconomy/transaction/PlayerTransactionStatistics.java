package de.sodaeconomy.transaction;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable audit aggregate for one player's wallet transaction history. */
public record PlayerTransactionStatistics(UUID playerId, long transactionCount, long totalReceivedMinor,
                                          long totalSentMinor, long largestReceivedMinor, long largestSentMinor,
                                          Instant firstTransactionAt, Instant lastTransactionAt) {

    public PlayerTransactionStatistics {
        playerId = Objects.requireNonNull(playerId, "playerId");
        if (transactionCount < 0L || totalReceivedMinor < 0L || totalSentMinor < 0L
                || largestReceivedMinor < 0L || largestSentMinor < 0L) {
            throw new IllegalArgumentException("Player transaction statistics must not contain negative values");
        }
        if (transactionCount == 0L && (firstTransactionAt != null || lastTransactionAt != null)) {
            throw new IllegalArgumentException("An empty transaction history cannot have timestamps");
        }
        if (transactionCount > 0L && (firstTransactionAt == null || lastTransactionAt == null
                || firstTransactionAt.isAfter(lastTransactionAt))) {
            throw new IllegalArgumentException("A non-empty transaction history requires an ordered time range");
        }
    }

    /** Returns the signed wallet balance change represented by successful transactions. */
    public long netChangeMinor() {
        return Math.subtractExact(totalReceivedMinor, totalSentMinor);
    }

    /** Aggregates records already limited to, or containing, the supplied player. */
    public static PlayerTransactionStatistics fromRecords(UUID playerId, List<TransactionRecord> records) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(records, "records");
        long count = 0L;
        long received = 0L;
        long sent = 0L;
        long largestReceived = 0L;
        long largestSent = 0L;
        Instant first = null;
        Instant last = null;

        for (TransactionRecord record : records) {
            if (!participates(playerId, record)) {
                continue;
            }
            count = Math.addExact(count, 1L);
            first = first == null || record.timestamp().isBefore(first) ? record.timestamp() : first;
            last = last == null || record.timestamp().isAfter(last) ? record.timestamp() : last;
            long[] flow = TransactionRecords.playerWalletFlows(record, playerId);
            received = Math.addExact(received, flow[0]);
            sent = Math.addExact(sent, flow[1]);
            largestReceived = Math.max(largestReceived, flow[0]);
            largestSent = Math.max(largestSent, flow[1]);
        }
        return new PlayerTransactionStatistics(playerId, count, received, sent, largestReceived, largestSent, first, last);
    }

    private static boolean participates(UUID playerId, TransactionRecord record) {
        return playerId.equals(record.sourcePlayerId()) || playerId.equals(record.targetPlayerId());
    }
}
