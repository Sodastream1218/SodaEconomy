package de.sodaeconomy.commands;

import be.seeseemelk.mockbukkit.entity.PlayerMock;
import de.sodaeconomy.support.MockBukkitTestBase;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TopBalanceCommandTest extends MockBukkitTestBase {

    @Test
    void limitsRequestedEntriesToTheConfiguredMaximum() throws Exception {
        PlayerMock viewer = server.addPlayer("Viewer");
        PlayerMock first = server.addPlayer("First");
        PlayerMock second = server.addPlayer("Second");
        plugin.getEconomyManager().setBalance(viewer.getUniqueId(), 100D);
        plugin.getEconomyManager().setBalance(first.getUniqueId(), 300D);
        plugin.getEconomyManager().setBalance(second.getUniqueId(), 200D);
        TopBalanceCommand command = new TopBalanceCommand(plugin.getEconomyManager(), 1, 2);

        assertTrue(command.onCommand(viewer, null, "topbalance", new String[]{"999999"}));
        awaitAsyncCommandResult();

        viewer.assertSaid(plugin.getLanguageManager().getFormattedMessage("topbalance-header", "count", 2));
    }

    @Test
    void providesTheLocalizedDisabledFeatureMessage() {
        PlayerMock player = server.addPlayer("Alice");
        DisabledFeatureCommand command = new DisabledFeatureCommand("leaderboard-disabled");

        assertTrue(command.onCommand(player, null, "topbalance", new String[0]));

        player.assertSaid(plugin.getLanguageManager().getFormattedMessage("leaderboard-disabled"));
    }
    @Test
    void usesReloadedLeaderboardSettingsWithoutReplacingStorageOrServices() throws Exception {
        PlayerMock viewer = server.addPlayer("Viewer");
        viewer.addAttachment(plugin, "sodaeconomy.topbalance", true);
        PlayerMock first = server.addPlayer("First");
        PlayerMock second = server.addPlayer("Second");
        plugin.getEconomyManager().setBalance(first.getUniqueId(), 300D);
        plugin.getEconomyManager().setBalance(second.getUniqueId(), 200D);
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration disk = YamlConfiguration.loadConfiguration(file);
        disk.set("leaderboard.enabled", false);
        disk.save(file);
        plugin.reloadSafeRuntimeConfiguration();

        assertTrue(server.dispatchCommand(viewer, "topbalance"));
        viewer.assertSaid(plugin.getLanguageManager().getFormattedMessage("leaderboard-disabled"));

        disk = YamlConfiguration.loadConfiguration(file);
        disk.set("leaderboard.enabled", true);
        disk.set("leaderboard.top-entries", 1);
        disk.set("leaderboard.max-entries", 1);
        disk.save(file);
        plugin.reloadSafeRuntimeConfiguration();

        assertTrue(server.dispatchCommand(viewer, "topbalance 99"));
        awaitAsyncCommandResult();
        viewer.assertSaid(plugin.getLanguageManager().getFormattedMessage("topbalance-header", "count", 1));
    }

}
