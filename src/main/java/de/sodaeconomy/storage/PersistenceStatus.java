package de.sodaeconomy.storage;

import java.time.Instant;

/** Immutable, thread-safe diagnostic snapshot of local asynchronous persistence. */
public record PersistenceStatus(
        PersistenceHealth health,
        int pendingWriteCount,
        int queueCapacity,
        int consecutiveFailures,
        Instant oldestPendingWriteAt,
        Instant lastSuccessfulWriteAt,
        Instant lastFailureAt,
        String lastFailureSummary,
        Instant nextAttemptAt
) {
}
