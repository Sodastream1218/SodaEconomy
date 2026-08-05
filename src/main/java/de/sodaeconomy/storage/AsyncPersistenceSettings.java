package de.sodaeconomy.storage;

/**
 * Validated settings for the ordered local asynchronous persistence queue.
 *
 * <p>The queue is intentionally single-threaded. A wallet mutation is allowed to reach the
 * backend only after every earlier mutation has been committed, preserving the same ordering as
 * the in-memory economy state. It is used only for single-server storage backends; MySQL uses
 * database-authoritative persistence instead.</p>
 */
public record AsyncPersistenceSettings(long initialRetryDelayMillis, long maximumRetryDelayMillis,
                                       long shutdownWarningIntervalMillis, int queueCapacity,
                                       int warningThresholdPercent, int maximumRetryAttempts,
                                       long recoveryProbeIntervalMillis, long shutdownTimeoutMillis) {
    public static final int DEFAULT_QUEUE_CAPACITY = 10_000;
    public static final int DEFAULT_WARNING_THRESHOLD_PERCENT = 80;
    public static final int DEFAULT_MAXIMUM_RETRY_ATTEMPTS = 8;
    public static final long DEFAULT_RECOVERY_PROBE_INTERVAL_MILLIS = 30_000L;
    public static final long DEFAULT_SHUTDOWN_TIMEOUT_MILLIS = 30_000L;

    /**
     * Compatibility constructor for integrations and tests that used the original three timing
     * settings. New installations receive the bounded-queue defaults declared above.
     */
    public AsyncPersistenceSettings(long initialRetryDelayMillis, long maximumRetryDelayMillis,
                                    long shutdownWarningIntervalMillis) {
        this(initialRetryDelayMillis, maximumRetryDelayMillis, shutdownWarningIntervalMillis,
                DEFAULT_QUEUE_CAPACITY, DEFAULT_WARNING_THRESHOLD_PERCENT,
                DEFAULT_MAXIMUM_RETRY_ATTEMPTS, DEFAULT_RECOVERY_PROBE_INTERVAL_MILLIS,
                DEFAULT_SHUTDOWN_TIMEOUT_MILLIS);
    }

    public AsyncPersistenceSettings {
        if (initialRetryDelayMillis < 1L) {
            throw new IllegalArgumentException("The initial persistence retry delay must be positive");
        }
        if (maximumRetryDelayMillis < initialRetryDelayMillis) {
            throw new IllegalArgumentException("The maximum persistence retry delay must not be smaller than the initial delay");
        }
        if (shutdownWarningIntervalMillis < 1L) {
            throw new IllegalArgumentException("The persistence shutdown warning interval must be positive");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("The persistence queue capacity must be positive");
        }
        if (warningThresholdPercent < 1 || warningThresholdPercent > 100) {
            throw new IllegalArgumentException("The persistence warning threshold must be between 1 and 100 percent");
        }
        if (maximumRetryAttempts < 1) {
            throw new IllegalArgumentException("The maximum persistence retry attempts must be positive");
        }
        if (recoveryProbeIntervalMillis < 1L) {
            throw new IllegalArgumentException("The persistence recovery probe interval must be positive");
        }
        if (shutdownTimeoutMillis < shutdownWarningIntervalMillis) {
            throw new IllegalArgumentException("The persistence shutdown timeout must not be shorter than the warning interval");
        }
    }
}
