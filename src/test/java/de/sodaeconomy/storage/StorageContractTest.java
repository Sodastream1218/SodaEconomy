package de.sodaeconomy.storage;

import de.sodaeconomy.identity.PlayerIdentity;
import de.sodaeconomy.identity.PlayerType;
import de.sodaeconomy.support.MockBukkitTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shared observable storage behaviour, executed by YAML, SQLite, and MySQL implementations. */
abstract class StorageContractTest extends MockBukkitTestBase {
    private Storage storage;

    @BeforeEach
    final void initializeStorage() throws Exception {
        configureStorage();
        storage = createStorage();
        storage.init(plugin);
    }

    @AfterEach
    final void closeStorage() {
        if (storage != null) storage.close();
    }

    protected void configureStorage() { }

    protected abstract Storage createStorage();

    protected final Storage storage() {
        return storage;
    }

    @Test
    void returnsNullForMissingAccounts() throws Exception {
        assertNull(storage().getBalance(UUID.randomUUID()));
        assertNull(storage().getBankBalance(UUID.randomUUID()));
    }

    @Test
    void createsAnAccountOnlyOnceAndPreservesItsInitialBalance() throws Exception {
        UUID playerId = UUID.randomUUID();

        assertEquals(100D, storage().getOrCreateBalance(playerId, 100D));
        assertEquals(100D, storage().getOrCreateBalance(playerId, 999D));
        assertEquals(100D, storage().getBalance(playerId));
    }

    @Test
    void transfersExactlyTheRequestedAmountBetweenExistingAccounts() throws Exception {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        storage().getOrCreateBalance(source, 100D);
        storage().getOrCreateBalance(target, 20D);

        assertTrue(storage().transferMain(source, target, 25D));

        assertEquals(75D, storage().getBalance(source));
        assertEquals(45D, storage().getBalance(target));
    }

    @Test
    void rejectsNonFiniteValuesBeforeTheyReachTheStorage() {
        assertThrows(IllegalArgumentException.class, () -> storage().setBalance(UUID.randomUUID(), Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> storage().setBankBalance(UUID.randomUUID(), Double.POSITIVE_INFINITY));
    }

    @Test
    void replacesTheCompleteMigrationSnapshotWithoutKeepingOldBalances() throws Exception {
        UUID previousPlayer = UUID.randomUUID();
        UUID migratedPlayer = UUID.randomUUID();
        storage().getOrCreateBalance(previousPlayer, 50D);
        storage().setBankBalance(previousPlayer, 10D);

        storage().replaceSnapshot(new StorageSnapshot(Map.of(migratedPlayer, 125D), Map.of(migratedPlayer, 25D)));

        assertEquals(Map.of(migratedPlayer, 125D), storage().getAllBalances());
        assertEquals(Map.of(migratedPlayer, 25D), storage().getAllBankBalances());
    }
    @Test
    void persistsAndResolvesKnownPlayerIdentitiesWithoutCreatingSyntheticAccounts() throws Exception {
        UUID playerId = UUID.randomUUID();
        PlayerIdentity identity = PlayerIdentity.of(playerId, ".BedrockUser",
                Instant.ofEpochMilli(1_700_000_000_000L), PlayerType.BEDROCK);

        storage().upsertPlayerIdentity(identity);

        assertEquals(identity, storage().findPlayerIdentity(playerId).orElseThrow());
        assertEquals(List.of(identity), storage().findPlayerIdentitiesByNormalizedName(".bedrockuser"));
        assertNull(storage().getBalance(playerId), "Identity persistence must not create an economy account");
    }

    @Test
    void identityUpsertsUseNewestLoginForNamesAndNeverDowngradeBedrock() throws Exception {
        UUID playerId = UUID.randomUUID();
        PlayerIdentity current = PlayerIdentity.of(playerId, "CurrentName",
                Instant.ofEpochMilli(20L), PlayerType.JAVA);
        PlayerIdentity staleBedrockObservation = PlayerIdentity.of(playerId, ".StaleName",
                Instant.ofEpochMilli(10L), PlayerType.BEDROCK);

        storage().upsertPlayerIdentity(current);
        storage().upsertPlayerIdentity(staleBedrockObservation);

        PlayerIdentity stored = storage().findPlayerIdentity(playerId).orElseThrow();
        assertEquals("CurrentName", stored.lastKnownName());
        assertEquals(Instant.ofEpochMilli(20L), stored.lastLoginAt());
        assertEquals(PlayerType.BEDROCK, stored.playerType());
    }

    @Test
    void legacyThreePartSnapshotReplacementPreservesTheIdentityRegistry() throws Exception {
        UUID playerId = UUID.randomUUID();
        PlayerIdentity identity = PlayerIdentity.of(playerId, "KnownPlayer",
                Instant.ofEpochMilli(123L), PlayerType.JAVA);
        storage().upsertPlayerIdentity(identity);

        storage().replaceMinorUnitSnapshot(Map.of(playerId, 500L), Map.of(), List.of());

        assertEquals(identity, storage().findPlayerIdentity(playerId).orElseThrow());
        assertEquals(500L, storage().getBalanceMinorUnits(playerId));
        assertEquals(500L, storage().getAllBalanceMinorUnits().get(playerId));
    }

    @Test
    void migratesPlayerIdentitiesAsPartOfTheExactStorageSnapshot() throws Exception {
        UUID oldPlayer = UUID.randomUUID();
        UUID migratedPlayer = UUID.randomUUID();
        storage().upsertPlayerIdentity(PlayerIdentity.of(oldPlayer, "OldName", Instant.EPOCH, PlayerType.JAVA));
        PlayerIdentity migratedIdentity = PlayerIdentity.of(migratedPlayer, "NewName",
                Instant.ofEpochMilli(42L), PlayerType.UNKNOWN);

        storage().replaceMinorUnitSnapshot(Map.of(), Map.of(), List.of(),
                Map.of(migratedPlayer, migratedIdentity));

        assertFalse(storage().findPlayerIdentity(oldPlayer).isPresent());
        assertEquals(Map.of(migratedPlayer, migratedIdentity), storage().getAllPlayerIdentities());
    }

}
