package de.sodaeconomy.transaction;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Shared validation rules for transaction commands and immutable journal records. */
final class TransactionValidation {
    static final int MAX_REASON_LENGTH = 512;
    static final int MAX_METADATA_ENTRIES = 32;
    static final int MAX_METADATA_VALUE_LENGTH = 512;
    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private TransactionValidation() {
    }

    static String normalizeReason(String value) {
        if (value == null || value.isBlank()) {
            return "Unspecified";
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("The transaction reason is too long");
        }
        return normalized;
    }

    static Map<String, String> immutableMetadata(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        if (values.size() > MAX_METADATA_ENTRIES) {
            throw new IllegalArgumentException("Too many metadata entries");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "metadata key").trim();
            String value = Objects.requireNonNull(entry.getValue(), "metadata value").trim();
            if (key.isEmpty() || key.length() > MAX_METADATA_VALUE_LENGTH
                    || value.length() > MAX_METADATA_VALUE_LENGTH) {
                throw new IllegalArgumentException("Invalid transaction metadata");
            }
            result.put(key, value);
        }
        return Map.copyOf(result);
    }

    static String normalizeIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException("The idempotency key is too long");
        }
        return normalized;
    }

    static void validateRequestType(TransactionType type, WalletOperation operation, UUID reversalOfTransactionId) {
        if (type.isBootstrapRecord()) {
            throw new IllegalArgumentException("Opening-balance transaction types are generated only by storage initialization");
        }
        validateOperationCompatibility(type, operation);
        if (type == TransactionType.ROLLBACK && reversalOfTransactionId == null) {
            throw new IllegalArgumentException("A rollback transaction requires the original transaction ID");
        }
        if (type != TransactionType.ROLLBACK && reversalOfTransactionId != null) {
            throw new IllegalArgumentException("Only rollback transactions may reference an original transaction");
        }
    }

    static void validateRecordShape(TransactionType type, TransactionStatus status, WalletOperation operation,
                                    UUID sourcePlayerId, UUID targetPlayerId, UUID reversalOfTransactionId) {
        if (type.isBootstrapRecord()) {
            if (status != TransactionStatus.SUCCESS || operation != WalletOperation.CREDIT
                    || reversalOfTransactionId != null) {
                throw new IllegalArgumentException("Opening-balance records must be successful credit records");
            }
        } else {
            validateOperationCompatibility(type, operation);
            if (type == TransactionType.ROLLBACK && reversalOfTransactionId == null) {
                throw new IllegalArgumentException("A rollback record requires the original transaction ID");
            }
            if (type != TransactionType.ROLLBACK && reversalOfTransactionId != null) {
                throw new IllegalArgumentException("Only rollback records may reference an original transaction");
            }
        }
        validateParticipants(operation, sourcePlayerId, targetPlayerId);
    }

    static void validateParticipants(WalletOperation operation, UUID source, UUID target) {
        switch (operation) {
            case CREDIT, SET -> {
                if (source != null || target == null) {
                    throw new IllegalArgumentException(operation + " requires only a target player");
                }
            }
            case DEBIT -> {
                if (source == null || target != null) {
                    throw new IllegalArgumentException("A debit requires only a source player");
                }
            }
            case TRANSFER -> {
                if (source == null || target == null || source.equals(target)) {
                    throw new IllegalArgumentException("A transfer requires two different players");
                }
            }
        }
    }

    private static void validateOperationCompatibility(TransactionType type, WalletOperation operation) {
        if (!type.supports(operation)) {
            throw new IllegalArgumentException("The transaction type does not support the requested wallet operation");
        }
    }
}
