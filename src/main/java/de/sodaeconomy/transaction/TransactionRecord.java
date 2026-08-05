package de.sodaeconomy.transaction;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable, append-only wallet journal record. All amount and balance fields use minor units. */
public record TransactionRecord(
        UUID id,
        Instant timestamp,
        TransactionType type,
        TransactionStatus status,
        TransactionOrigin origin,
        WalletOperation operation,
        UUID sourcePlayerId,
        UUID targetPlayerId,
        long requestedAmountMinor,
        long appliedAmountMinor,
        Long sourceBalanceBeforeMinor,
        Long sourceBalanceAfterMinor,
        Long targetBalanceBeforeMinor,
        Long targetBalanceAfterMinor,
        String reason,
        Map<String, String> metadata,
        UUID reversalOfTransactionId,
        UUID batchId,
        String idempotencyKey,
        TransactionFailureReason failureReason,
        String failureDetail
) {
    public TransactionRecord {
        id = Objects.requireNonNull(id, "id");
        timestamp = Objects.requireNonNull(timestamp, "timestamp").truncatedTo(ChronoUnit.MILLIS);
        type = Objects.requireNonNull(type, "type");
        status = Objects.requireNonNull(status, "status");
        origin = Objects.requireNonNull(origin, "origin");
        operation = Objects.requireNonNull(operation, "operation");
        reason = TransactionValidation.normalizeReason(reason);
        metadata = TransactionValidation.immutableMetadata(metadata);
        idempotencyKey = TransactionValidation.normalizeIdempotencyKey(idempotencyKey);
        failureReason = Objects.requireNonNullElse(failureReason, TransactionFailureReason.NONE);
        failureDetail = failureDetail == null ? null : failureDetail.trim();
        if (requestedAmountMinor < 0 || appliedAmountMinor < 0) {
            throw new IllegalArgumentException("Transaction amounts must not be negative");
        }
        if (status == TransactionStatus.SUCCESS && failureReason != TransactionFailureReason.NONE) {
            throw new IllegalArgumentException("Successful transactions cannot have a failure reason");
        }
        if (status != TransactionStatus.SUCCESS && failureReason == TransactionFailureReason.NONE) {
            throw new IllegalArgumentException("Unsuccessful transactions require a failure reason");
        }
        if (status != TransactionStatus.SUCCESS && appliedAmountMinor != 0L) {
            throw new IllegalArgumentException("Unsuccessful transactions cannot apply an amount");
        }
        TransactionValidation.validateRecordShape(type, status, operation, sourcePlayerId, targetPlayerId,
                reversalOfTransactionId);
        validateBalance(sourceBalanceBeforeMinor);
        validateBalance(sourceBalanceAfterMinor);
        validateBalance(targetBalanceBeforeMinor);
        validateBalance(targetBalanceAfterMinor);
    }

    public boolean isSuccessful() {
        return status == TransactionStatus.SUCCESS;
    }

    private static void validateBalance(Long value) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException("Wallet balances must not be negative");
        }
    }
}
