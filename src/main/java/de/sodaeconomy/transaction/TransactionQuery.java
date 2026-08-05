package de.sodaeconomy.transaction;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable, paginated filter for audit and transaction-history queries. */
public record TransactionQuery(
        UUID transactionId,
        UUID playerId,
        UUID sourcePlayerId,
        UUID targetPlayerId,
        TransactionType type,
        TransactionStatus status,
        Instant fromInclusive,
        Instant toExclusive,
        int offset,
        int limit
) {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    public TransactionQuery {
        if (offset < 0) {
            throw new IllegalArgumentException("The transaction query offset must not be negative");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("The transaction query limit must be between 1 and " + MAX_LIMIT);
        }
        if (fromInclusive != null && toExclusive != null && !fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("The transaction query time range is invalid");
        }
    }

    public static TransactionQuery recent() {
        return new TransactionQuery(null, null, null, null, null, null, null, null, 0, DEFAULT_LIMIT);
    }

    public TransactionQuery nextPage() {
        return new TransactionQuery(transactionId, playerId, sourcePlayerId, targetPlayerId, type, status,
                fromInclusive, toExclusive, Math.addExact(offset, limit), limit);
    }
}
