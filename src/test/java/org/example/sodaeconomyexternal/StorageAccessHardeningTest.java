package org.example.sodaeconomyexternal;

import be.seeseemelk.mockbukkit.MockBukkit;
import de.sodaeconomy.SodaEconomy;
import de.sodaeconomy.identity.PlayerIdentity;
import de.sodaeconomy.identity.PlayerIdentityApi;
import de.sodaeconomy.identity.PlayerType;
import de.sodaeconomy.storage.Storage;
import de.sodaeconomy.storage.UnauthorizedStorageAccessException;
import de.sodaeconomy.transaction.TransactionOrigin;
import de.sodaeconomy.transaction.UnsupportedTransactionServiceAccessException;
import de.sodaeconomy.transaction.TransactionResult;
import de.sodaeconomy.transaction.TransactionFailureReason;
import de.sodaeconomy.transaction.TransactionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Simulates a third-party plugin package using leaked low-level storage access. */
class StorageAccessHardeningTest {
    private SodaEconomy plugin;

    @BeforeEach
    void startPlugin() {
        MockBukkit.mock();
        plugin = MockBukkit.load(SodaEconomy.class);
    }

    @AfterEach
    void stopPlugin() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    @SuppressWarnings("removal")
    void externalStorageMutationThroughLegacyGetterIsRejected() throws Exception {
        Storage storage = plugin.getStorageManager().getCurrent();
        UUID playerId = UUID.randomUUID();

        UnauthorizedStorageAccessException exception = assertThrows(UnauthorizedStorageAccessException.class,
                () -> storage.setBalance(playerId, 500D));

        assertTrue(exception.getMessage().contains("EconomyTransactionApi"));
    }

    @Test
    @SuppressWarnings("removal")
    void externalExactSnapshotAccessThroughLegacyGetterIsRejected() throws Exception {
        Storage storage = plugin.getStorageManager().getCurrent();

        assertThrows(UnauthorizedStorageAccessException.class, storage::getAllBalanceMinorUnits);
        assertThrows(UnauthorizedStorageAccessException.class,
                () -> storage.replaceMinorUnitSnapshot(Map.of(), Map.of(), java.util.List.of()));
    }

    @Test
    @SuppressWarnings("removal")
    void externalIdentityStorageAccessIsRejectedInFavorOfTheReadOnlyApi() throws Exception {
        Storage storage = plugin.getStorageManager().getCurrent();
        PlayerIdentity identity = PlayerIdentity.of(UUID.randomUUID(), "ExternalPlayer", Instant.now(),
                PlayerType.UNKNOWN);

        UnauthorizedStorageAccessException mutationFailure = assertThrows(UnauthorizedStorageAccessException.class,
                () -> storage.upsertPlayerIdentity(identity));
        assertTrue(mutationFailure.getMessage().contains("PlayerIdentityApi"));
        assertThrows(UnauthorizedStorageAccessException.class, storage::getAllPlayerIdentities);

        PlayerIdentityApi api = plugin.getPlayerIdentityApi();
        assertTrue(api.resolve(identity.playerId()).join().isEmpty());
    }


    @Test
    @SuppressWarnings("removal")
    void externalConcreteTransactionServiceCommandMethodsAreRejected() {
        UUID playerId = UUID.randomUUID();

        UnsupportedTransactionServiceAccessException exception = assertThrows(UnsupportedTransactionServiceAccessException.class,
                () -> plugin.getTransactionService().depositAsynchronously(playerId, 25D,
                        TransactionType.ADMIN_GIVE, TransactionOrigin.console(), "External misuse", Map.of()));

        assertTrue(exception.getMessage().contains("EconomyTransactionApi"));
    }

    @Test
    void publicEconomyTransactionApiStillUsesTheInternalServicePath() {
        UUID playerId = UUID.randomUUID();

        TransactionResult result = plugin.getEconomyTransactionApi().deposit(playerId, 25D,
                TransactionType.API_DEPOSIT, TransactionOrigin.api("ExternalHardeningTest"),
                "API deposit", Map.of()).join();

        assertTrue(result.isSuccessful());
        assertEquals(TransactionFailureReason.NONE, result.failureReason());
        assertEquals(1_025D, plugin.getEconomyTransactionApi().getStoredBalance(playerId).join());
    }
}
