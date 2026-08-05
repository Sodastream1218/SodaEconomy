package de.sodaeconomy.identity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnownPlayerRegistryTest {
    @Test
    void resolvesNamesCaseInsensitivelyAndTracksRenames() {
        KnownPlayerRegistry registry = new KnownPlayerRegistry();
        UUID playerId = UUID.randomUUID();
        registry.upsert(PlayerIdentity.of(playerId, "FirstName", Instant.ofEpochMilli(1), PlayerType.JAVA));
        registry.upsert(PlayerIdentity.of(playerId, "SecondName", Instant.ofEpochMilli(2), PlayerType.JAVA));

        assertTrue(registry.findUnique("FIRSTNAME").isEmpty());
        assertEquals(playerId, registry.findUnique("secondname").orElseThrow().playerId());
    }

    @Test
    void keepsNameCollisionsExplicitInsteadOfSelectingAnArbitraryUuid() {
        KnownPlayerRegistry registry = new KnownPlayerRegistry();
        registry.upsert(PlayerIdentity.of(UUID.randomUUID(), "Shared", Instant.ofEpochMilli(1), PlayerType.JAVA));
        registry.upsert(PlayerIdentity.of(UUID.randomUUID(), "shared", Instant.ofEpochMilli(2), PlayerType.BEDROCK));

        assertTrue(registry.findUnique("Shared").isEmpty());
    }

    @Test
    void confirmedBedrockTypeCannotBeDowngradedByABackendWithoutFloodgateData() {
        UUID playerId = UUID.randomUUID();
        PlayerIdentity bedrock = PlayerIdentity.of(playerId, ".Alex", Instant.ofEpochMilli(1), PlayerType.BEDROCK);
        PlayerIdentity laterJavaObservation = PlayerIdentity.of(playerId, ".Alex", Instant.ofEpochMilli(2), PlayerType.JAVA);

        PlayerIdentity merged = PlayerIdentity.merge(bedrock, laterJavaObservation);

        assertEquals(PlayerType.BEDROCK, merged.playerType());
        assertEquals(Instant.ofEpochMilli(2), merged.lastLoginAt());
    }

    @Test
    void olderObservationsCannotRestoreAStaleName() {
        UUID playerId = UUID.randomUUID();
        PlayerIdentity current = PlayerIdentity.of(playerId, "CurrentName", Instant.ofEpochMilli(20), PlayerType.JAVA);
        PlayerIdentity stale = PlayerIdentity.of(playerId, "StaleName", Instant.ofEpochMilli(10), PlayerType.UNKNOWN);

        assertEquals(current, PlayerIdentity.merge(current, stale));
    }

    @Test
    void snapshotIsImmutableAndConsistent() {
        KnownPlayerRegistry registry = new KnownPlayerRegistry();
        UUID playerId = UUID.randomUUID();
        PlayerIdentity identity = PlayerIdentity.of(playerId, "Player", Instant.EPOCH, PlayerType.UNKNOWN);
        registry.upsert(identity);

        assertEquals(Map.of(playerId, identity), registry.snapshot());
    }
}
