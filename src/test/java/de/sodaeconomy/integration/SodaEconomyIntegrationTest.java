package de.sodaeconomy.integration;

import be.seeseemelk.mockbukkit.entity.PlayerMock;
import de.sodaeconomy.support.MockBukkitTestBase;
import de.sodaeconomy.transaction.EconomyTransactionApi;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SodaEconomyIntegrationTest extends MockBukkitTestBase {

    @Test
    void startsWithManagersAndDeclaredCommandsRegistered() {
        assertNotNull(plugin.getConfigManager());
        assertNotNull(plugin.getStorageManager());
        assertNotNull(plugin.getEconomyManager());
        assertNotNull(plugin.getEconomyTransactionApi());
        assertNotNull(server.getServicesManager().getRegistration(EconomyTransactionApi.class));
        assertNotNull(plugin.getLanguageManager());
        assertNotNull(plugin.getCommand("balance"));
        assertNotNull(plugin.getCommand("pay"));
        assertNotNull(plugin.getCommand("eco"));
        assertNotNull(plugin.getCommand("topbalance"));
        assertNotNull(plugin.getCommand("bank"));
        assertTrue(server.getServicesManager().getRegistration(Economy.class) == null,
                "Vault API may be on the test classpath, but no provider is registered without the Vault plugin.");
    }

    @Test
    void returnsLocalizedFeedbackWhenBankingIsDisabled() {
        PlayerMock player = server.addPlayer("Alice");
        player.addAttachment(plugin, "sodaeconomy.bank.use", true);

        assertTrue(server.dispatchCommand(player, "bank"));

        player.assertSaid(plugin.getLanguageManager().getFormattedMessage("banking-disabled"));
    }
    @Test
    void safeReloadDoesNotReplaceStorageOrTransactionServices() throws Exception {
        Object storageManager = plugin.getStorageManager();
        Object storage = plugin.getStorageManager().getCurrent();
        Object transactionService = plugin.getTransactionService();
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration disk = YamlConfiguration.loadConfiguration(file);
        disk.set("storage.type", "mysql");
        disk.set("banking.enabled", true);
        disk.set("persistence.async.enabled", false);
        disk.set("currency.symbol", "€");
        disk.save(file);

        plugin.reloadSafeRuntimeConfiguration();

        assertSame(storageManager, plugin.getStorageManager());
        assertSame(storage, plugin.getStorageManager().getCurrent());
        assertSame(transactionService, plugin.getTransactionService());
    }

}
