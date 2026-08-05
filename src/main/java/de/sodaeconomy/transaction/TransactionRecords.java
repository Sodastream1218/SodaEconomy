package de.sodaeconomy.transaction;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Factory methods shared by transaction storage implementations. */
public final class TransactionRecords {
    private TransactionRecords() {
    }

    public static TransactionRecord initialBalance(UUID playerId, long amountMinor, Instant timestamp) {
        return new TransactionRecord(
                UUID.randomUUID(), timestamp, TransactionType.INITIAL_BALANCE, TransactionStatus.SUCCESS,
                TransactionOrigin.system("Account initialization"), WalletOperation.CREDIT,
                null, playerId, amountMinor, amountMinor,
                null, null, 0L, amountMinor,
                "Initial account balance", Map.of(), null, null, null,
                TransactionFailureReason.NONE, null
        );
    }

    public static TransactionRecord legacyOpeningBalance(UUID playerId, long amountMinor, Instant timestamp) {
        return new TransactionRecord(
                UUID.randomUUID(), timestamp, TransactionType.LEGACY_OPENING_BALANCE, TransactionStatus.SUCCESS,
                TransactionOrigin.system("Ledger migration"), WalletOperation.CREDIT,
                null, playerId, amountMinor, amountMinor,
                null, null, 0L, amountMinor,
                "Opening balance imported when the transaction journal was enabled", Map.of(), null, null, null,
                TransactionFailureReason.NONE, null
        );
    }

    public static TransactionRecord successful(WalletTransactionRequest request, long appliedAmountMinor,
                                                Long sourceBeforeMinor, Long sourceAfterMinor,
                                                Long targetBeforeMinor, Long targetAfterMinor) {
        return new TransactionRecord(
                request.id(), request.timestamp(), request.type(), TransactionStatus.SUCCESS,
                request.origin(), request.operation(), request.sourcePlayerId(), request.targetPlayerId(),
                request.amountMinor(), appliedAmountMinor,
                sourceBeforeMinor, sourceAfterMinor, targetBeforeMinor, targetAfterMinor,
                request.reason(), request.metadata(), request.reversalOfTransactionId(), request.batchId(),
                request.idempotencyKey(), TransactionFailureReason.NONE, null
        );
    }

    public static TransactionRecord failed(WalletTransactionRequest request, TransactionFailureReason reason,
                                           String detail, Long sourceBalanceMinor, Long targetBalanceMinor) {
        return new TransactionRecord(
                request.id(), request.timestamp(), request.type(), TransactionStatus.FAILED,
                request.origin(), request.operation(), request.sourcePlayerId(), request.targetPlayerId(),
                request.amountMinor(), 0L,
                sourceBalanceMinor, sourceBalanceMinor, targetBalanceMinor, targetBalanceMinor,
                request.reason(), request.metadata(), request.reversalOfTransactionId(), request.batchId(),
                request.idempotencyKey(), reason, detail
        );
    }

    public static TransactionRecord storageFailure(WalletTransactionRequest request, String detail) {
        return new TransactionRecord(
                request.id(), request.timestamp(), request.type(), TransactionStatus.FAILED,
                request.origin(), request.operation(), request.sourcePlayerId(), request.targetPlayerId(),
                request.amountMinor(), 0L,
                null, null, null, null,
                request.reason(), request.metadata(), request.reversalOfTransactionId(), request.batchId(),
                request.idempotencyKey(), TransactionFailureReason.STORAGE_FAILURE, detail
        );
    }

    /**
     * Returns the wallet-money source and sink contribution of a successful record. Transfers and
     * wallet-bank movements merely relocate existing funds, so they do not affect money supply.
     */
    public static long[] moneySupplyFlows(TransactionRecord record) {
        if (record == null || !record.isSuccessful() || isInternalWalletMovement(record)) {
            return new long[] {0L, 0L};
        }
        return switch (record.operation()) {
            case CREDIT -> new long[] {record.appliedAmountMinor(), 0L};
            case DEBIT -> new long[] {0L, record.appliedAmountMinor()};
            case TRANSFER -> new long[] {0L, 0L};
            case SET -> moneySupplyFlowsForSet(record);
        };
    }

    /** Returns the successful wallet credit and debit contribution for one participant. */
    public static long[] playerWalletFlows(TransactionRecord record, UUID playerId) {
        if (record == null || playerId == null || !record.isSuccessful()) {
            return new long[] {0L, 0L};
        }
        return switch (record.operation()) {
            case CREDIT -> playerId.equals(record.targetPlayerId())
                    ? new long[] {record.appliedAmountMinor(), 0L} : new long[] {0L, 0L};
            case DEBIT -> playerId.equals(record.sourcePlayerId())
                    ? new long[] {0L, record.appliedAmountMinor()} : new long[] {0L, 0L};
            case TRANSFER -> {
                if (playerId.equals(record.sourcePlayerId())) {
                    yield new long[] {0L, record.appliedAmountMinor()};
                }
                yield playerId.equals(record.targetPlayerId())
                        ? new long[] {record.appliedAmountMinor(), 0L} : new long[] {0L, 0L};
            }
            case SET -> playerSetFlows(record, playerId);
        };
    }

    /** Returns the successful non-rollback transfer volume represented by a record. */
    public static long transferVolume(TransactionRecord record) {
        if (record == null || !record.isSuccessful()) {
            return 0L;
        }
        return record.type() == TransactionType.PLAYER_TRANSFER || record.type() == TransactionType.API_TRANSFER
                ? record.appliedAmountMinor() : 0L;
    }

    private static boolean isInternalWalletMovement(TransactionRecord record) {
        if (record.operation() == WalletOperation.TRANSFER
                || record.type() == TransactionType.WALLET_TO_BANK
                || record.type() == TransactionType.BANK_TO_WALLET) {
            return true;
        }
        if ("true".equals(record.metadata().get("internal_wallet_movement"))) {
            return true;
        }
        String reversedType = record.metadata().get("reversed_type");
        return TransactionType.WALLET_TO_BANK.name().equals(reversedType)
                || TransactionType.BANK_TO_WALLET.name().equals(reversedType)
                || TransactionType.PLAYER_TRANSFER.name().equals(reversedType)
                || TransactionType.API_TRANSFER.name().equals(reversedType);
    }

    private static long[] moneySupplyFlowsForSet(TransactionRecord record) {
        Long before = record.targetBalanceBeforeMinor();
        Long after = record.targetBalanceAfterMinor();
        if (before == null || after == null) {
            return new long[] {0L, 0L};
        }
        return after >= before
                ? new long[] {after - before, 0L}
                : new long[] {0L, before - after};
    }

    private static long[] playerSetFlows(TransactionRecord record, UUID playerId) {
        if (!playerId.equals(record.targetPlayerId()) || record.targetBalanceBeforeMinor() == null
                || record.targetBalanceAfterMinor() == null) {
            return new long[] {0L, 0L};
        }
        long before = record.targetBalanceBeforeMinor();
        long after = record.targetBalanceAfterMinor();
        return after >= before ? new long[] {after - before, 0L} : new long[] {0L, before - after};
    }
}
