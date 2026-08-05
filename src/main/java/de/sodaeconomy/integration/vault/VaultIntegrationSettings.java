package de.sodaeconomy.integration.vault;

/** Validated startup-only settings for the optional Vault provider. */
public record VaultIntegrationSettings(boolean enabled, long operationTimeoutMillis, long warnAfterMillis) {
    public static final long DEFAULT_OPERATION_TIMEOUT_MILLIS = 3_000L;
    public static final long DEFAULT_WARN_AFTER_MILLIS = 100L;
    public static final long MAXIMUM_OPERATION_TIMEOUT_MILLIS = 60_000L;

    public VaultIntegrationSettings {
        if (operationTimeoutMillis < 100L || operationTimeoutMillis > MAXIMUM_OPERATION_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException("integrations.vault.operation-timeout-millis must be between 100 and "
                    + MAXIMUM_OPERATION_TIMEOUT_MILLIS);
        }
        if (warnAfterMillis < 0L || warnAfterMillis > operationTimeoutMillis) {
            throw new IllegalArgumentException("integrations.vault.warn-after-millis must be between 0 and the operation timeout");
        }
    }
}
