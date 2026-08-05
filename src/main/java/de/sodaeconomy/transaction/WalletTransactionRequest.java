package de.sodaeconomy.transaction;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A fully validated command for one atomic wallet mutation. Amounts are expressed in minor
 * currency units to keep journal persistence exact.
 */
public record WalletTransactionRequest(
        UUID id,
        Instant timestamp,
        TransactionType type,
        TransactionOrigin origin,
        WalletOperation operation,
        UUID sourcePlayerId,
        UUID targetPlayerId,
        long amountMinor,
        Long targetBalanceMinor,
        String reason,
        Map<String, String> metadata,
        UUID reversalOfTransactionId,
        UUID batchId,
        String idempotencyKey
) {
    public WalletTransactionRequest {
        id = Objects.requireNonNull(id, "id");
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
        type = Objects.requireNonNull(type, "type");
        origin = Objects.requireNonNull(origin, "origin");
        operation = Objects.requireNonNull(operation, "operation");
        reason = TransactionValidation.normalizeReason(reason);
        metadata = TransactionValidation.immutableMetadata(metadata);
        idempotencyKey = TransactionValidation.normalizeIdempotencyKey(idempotencyKey);
        TransactionValidation.validateRequestType(type, operation, reversalOfTransactionId);
        TransactionValidation.validateParticipants(operation, sourcePlayerId, targetPlayerId);
        if (amountMinor < 0) {
            throw new IllegalArgumentException("The amount must not be negative");
        }
        if (operation != WalletOperation.SET && amountMinor == 0) {
            throw new IllegalArgumentException("The amount must be positive");
        }
        if (operation == WalletOperation.SET) {
            if (targetBalanceMinor == null || targetBalanceMinor < 0) {
                throw new IllegalArgumentException("A set operation requires a non-negative target balance");
            }
        } else if (targetBalanceMinor != null) {
            throw new IllegalArgumentException("Only a set operation may define a target balance");
        }
    }

}
