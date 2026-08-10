package de.sodaeconomy.transaction;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EconomyTransactionApiTest {

    private static final TransactionOrigin API_ORIGIN = TransactionOrigin.api("ApiContractTest");

    @Test
    void convenienceDepositUsesTheOnlyValidPublicDepositType() {
        RecordingApi api = new RecordingApi();
        UUID targetPlayerId = UUID.randomUUID();
        TransactionRequestOptions options = TransactionRequestOptions.of("Quest reward", Map.of("quest", "starter"));

        api.deposit(targetPlayerId, 25D, API_ORIGIN, options).join();

        assertEquals(TransactionType.API_DEPOSIT, api.type);
        assertNull(api.sourcePlayerId);
        assertEquals(targetPlayerId, api.targetPlayerId);
        assertEquals(API_ORIGIN, api.origin);
        assertEquals("Quest reward", api.reason);
        assertEquals(Map.of("quest", "starter"), api.metadata);
    }

    @Test
    void compatibilityDefaultRejectsIdempotencyKeysInsteadOfDroppingThem() {
        RecordingApi api = new RecordingApi();

        CompletionException exception = assertThrows(CompletionException.class, () -> api.deposit(UUID.randomUUID(), 25D,
                API_ORIGIN, TransactionRequestOptions.idempotent("Quest reward", Map.of(), "reward-42")).join());

        assertInstanceOf(UnsupportedOperationException.class, exception.getCause());
        assertNull(api.type);
    }

    @Test
    void compatibilityDefaultMakesUnsupportedStoredBalanceReadsExplicit() {
        RecordingApi api = new RecordingApi();

        CompletionException exception = assertThrows(CompletionException.class,
                () -> api.getStoredBalance(UUID.randomUUID()).join());

        assertInstanceOf(UnsupportedOperationException.class, exception.getCause());
    }

    @Test
    void compatibilityDefaultsMakeUnsupportedExactSnapshotsExplicit() {
        RecordingApi api = new RecordingApi();

        CompletionException wallets = assertThrows(CompletionException.class,
                () -> api.getStoredBalancesMinorUnits().join());
        CompletionException banks = assertThrows(CompletionException.class,
                () -> api.getStoredBankBalancesMinorUnits().join());

        assertInstanceOf(UnsupportedOperationException.class, wallets.getCause());
        assertInstanceOf(UnsupportedOperationException.class, banks.getCause());
    }

    @Test
    void transactionRequestOptionsDefensivelyCopyMetadataAndNormalizeIdempotencyKeys() {
        Map<String, String> mutableMetadata = new java.util.LinkedHashMap<>();
        mutableMetadata.put("source", "quest");

        TransactionRequestOptions options = TransactionRequestOptions.idempotent("Reward", mutableMetadata, " reward-42 ");
        mutableMetadata.put("later", "change");

        assertEquals(Map.of("source", "quest"), options.metadata());
        assertEquals("reward-42", options.idempotencyKey());
        assertThrows(UnsupportedOperationException.class, () -> options.metadata().put("other", "value"));
    }

    @Test
    void transactionRequestOptionsRejectBlankIdempotencyKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> TransactionRequestOptions.idempotent("Reward", Map.of(), "  "));
    }

    private static final class RecordingApi implements EconomyTransactionApi {
        private TransactionType type;
        private TransactionOrigin origin;
        private UUID sourcePlayerId;
        private UUID targetPlayerId;
        private String reason;
        private Map<String, String> metadata;

        @Override
        public CompletableFuture<TransactionResult> deposit(UUID targetPlayerId, double amount, TransactionType type,
                                                             TransactionOrigin origin, String reason,
                                                             Map<String, String> metadata) {
            capture(type, origin, null, targetPlayerId, reason, metadata);
            return completedResult();
        }

        @Override
        public CompletableFuture<TransactionResult> withdraw(UUID sourcePlayerId, double amount, TransactionType type,
                                                              TransactionOrigin origin, String reason,
                                                              Map<String, String> metadata) {
            capture(type, origin, sourcePlayerId, null, reason, metadata);
            return completedResult();
        }

        @Override
        public CompletableFuture<TransactionResult> transfer(UUID sourcePlayerId, UUID targetPlayerId, double amount,
                                                              TransactionType type, TransactionOrigin origin, String reason,
                                                              Map<String, String> metadata) {
            capture(type, origin, sourcePlayerId, targetPlayerId, reason, metadata);
            return completedResult();
        }

        @Override
        public CompletableFuture<TransactionResult> setBalance(UUID targetPlayerId, double targetBalance,
                                                                TransactionType type, TransactionOrigin origin,
                                                                String reason, Map<String, String> metadata) {
            capture(type, origin, null, targetPlayerId, reason, metadata);
            return completedResult();
        }

        @Override
        public CompletableFuture<TransactionResult> rollback(UUID transactionId, TransactionOrigin origin, String reason) {
            return completedResult();
        }

        @Override
        public CompletableFuture<TransactionPage> findTransactions(TransactionQuery query) {
            return CompletableFuture.completedFuture(new TransactionPage(List.of(), 0, 1, false));
        }

        @Override
        public CompletableFuture<EconomyStatistics> getStatistics() {
            return CompletableFuture.completedFuture(new EconomyStatistics(0L, 0L, null, 0L, 0L, 0L,
                    0L, 0L, 0L, 0L));
        }

        private void capture(TransactionType capturedType, TransactionOrigin capturedOrigin, UUID source, UUID target,
                             String capturedReason, Map<String, String> capturedMetadata) {
            type = capturedType;
            origin = capturedOrigin;
            sourcePlayerId = source;
            targetPlayerId = target;
            reason = capturedReason;
            metadata = capturedMetadata;
        }

        private static CompletableFuture<TransactionResult> completedResult() {
            return CompletableFuture.completedFuture(TransactionResult.failure(null,
                    TransactionFailureReason.INVALID_REQUEST));
        }
    }
}
