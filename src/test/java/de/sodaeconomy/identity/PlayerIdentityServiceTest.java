package de.sodaeconomy.identity;

import be.seeseemelk.mockbukkit.entity.PlayerMock;
import de.sodaeconomy.support.MockBukkitTestBase;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerIdentityServiceTest extends MockBukkitTestBase {
    @Test
    void observesOnlinePlayersAndPublishesTheReadOnlyApi() {
        PlayerMock player = server.addPlayer("CrossplayUser");

        PlayerIdentityApi api = plugin.getPlayerIdentityApi();
        RegisteredServiceProvider<PlayerIdentityApi> registration = server.getServicesManager()
                .getRegistration(PlayerIdentityApi.class);

        assertNotNull(registration);
        assertEquals(api, registration.getProvider());
        PlayerIdentity identity = api.findCached(player.getUniqueId()).orElseThrow();
        assertEquals("CrossplayUser", identity.lastKnownName());
        assertEquals(PlayerType.JAVA, identity.playerType());
        assertEquals(player, api.findOnlinePlayer("crossplayuser").orElseThrow());
    }

    @Test
    void unknownNamesDoNotCreateSyntheticOfflineAccounts() throws Exception {
        Map<UUID, Double> balancesBefore = plugin.getStorageManager().getCurrent().getAllBalances();

        assertTrue(plugin.getPlayerIdentityApi().resolve("DefinitelyUnknown").get(5, TimeUnit.SECONDS).isEmpty());
        assertEquals(balancesBefore, plugin.getStorageManager().getCurrent().getAllBalances());
        assertFalse(plugin.getPlayerIdentityApi().findCached("DefinitelyUnknown").isPresent());
    }

    @Test
    void persistentNameCollisionsRemainUnresolved() throws Exception {
        PlayerIdentity first = PlayerIdentity.of(UUID.randomUUID(), "SharedName",
                Instant.ofEpochMilli(10L), PlayerType.JAVA);
        PlayerIdentity second = PlayerIdentity.of(UUID.randomUUID(), "sharedname",
                Instant.ofEpochMilli(20L), PlayerType.BEDROCK);
        plugin.getStorageManager().getCurrent().upsertPlayerIdentity(first);
        plugin.getStorageManager().getCurrent().upsertPlayerIdentity(second);

        assertTrue(plugin.getPlayerIdentityApi().resolve("SHAREDNAME")
                .get(5, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    void persistentResolutionRefreshesAStaleNodeLocalName() throws Exception {
        UUID playerId = UUID.randomUUID();
        PlayerIdentity oldIdentity = PlayerIdentity.of(playerId, "OldName", Instant.ofEpochMilli(10), PlayerType.JAVA);
        PlayerIdentity renamedIdentity = PlayerIdentity.of(playerId, "NewName", Instant.ofEpochMilli(20), PlayerType.JAVA);
        plugin.getStorageManager().getCurrent().upsertPlayerIdentity(oldIdentity);

        assertEquals("OldName", plugin.getPlayerIdentityApi().resolve(playerId)
                .get(5, TimeUnit.SECONDS).orElseThrow().lastKnownName());

        plugin.getStorageManager().getCurrent().upsertPlayerIdentity(renamedIdentity);

        assertEquals("NewName", plugin.getPlayerIdentityApi().resolve(playerId)
                .get(5, TimeUnit.SECONDS).orElseThrow().lastKnownName());
        assertEquals("NewName", plugin.getPlayerIdentityApi().resolveDisplayNames(java.util.List.of(playerId))
                .get(5, TimeUnit.SECONDS).get(playerId));
        assertTrue(plugin.getPlayerIdentityApi().resolve("OldName").get(5, TimeUnit.SECONDS).isEmpty());
        assertEquals(playerId, plugin.getPlayerIdentityApi().resolve("newname")
                .get(5, TimeUnit.SECONDS).orElseThrow().playerId());
    }
}
