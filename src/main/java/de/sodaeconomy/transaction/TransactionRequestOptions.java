package de.sodaeconomy.transaction;

import java.util.Map;

/**
 * Immutable optional data supplied with a public economy transaction request.
 *
 * <p>The reason and metadata are stored in the immutable wallet journal. The optional
 * idempotency key identifies a caller retry of the same logical operation and must therefore be
 * stable and unique within the caller's own retry scope. It is not a player-visible identifier.</p>
 */
public record TransactionRequestOptions(String reason, Map<String, String> metadata, String idempotencyKey) {

    /** Shared empty options with the normal unspecified-reason behaviour. */
    public static final TransactionRequestOptions EMPTY = new TransactionRequestOptions(null, Map.of(), null);

    public TransactionRequestOptions {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (idempotencyKey != null) {
            String normalizedKey = idempotencyKey.trim();
            if (normalizedKey.isEmpty()) {
                throw new IllegalArgumentException("The idempotency key must not be blank");
            }
            idempotencyKey = normalizedKey;
        }
    }

    /** Creates options without an idempotency key. */
    public static TransactionRequestOptions of(String reason, Map<String, String> metadata) {
        return new TransactionRequestOptions(reason, metadata, null);
    }

    /** Creates options for one idempotent external transaction request. */
    public static TransactionRequestOptions idempotent(String reason, Map<String, String> metadata,
                                                        String idempotencyKey) {
        return new TransactionRequestOptions(reason, metadata, idempotencyKey);
    }
}
