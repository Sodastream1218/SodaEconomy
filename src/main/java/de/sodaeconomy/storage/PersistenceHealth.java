package de.sodaeconomy.storage;

/**
 * Health state of the local asynchronous persistence queue.
 *
 * <p>{@link #PAUSED} retains accepted writes at the queue head and rejects new mutations until a
 * bounded recovery probe succeeds. It is intentionally different from {@link #STOPPED}, where no
 * worker can persist additional writes.</p>
 */
public enum PersistenceHealth {
    HEALTHY,
    DEGRADED,
    PAUSED,
    DRAINING,
    STOPPED
}
