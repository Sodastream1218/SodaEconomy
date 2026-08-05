package de.sodaeconomy.storage;

import de.sodaeconomy.support.MockBukkitTestBase;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigManagerTest extends MockBukkitTestBase {

    @Test
    void exposesDocumentedDefaultConfigurationValues() {
        ConfigManager config = plugin.getConfigManager();

        assertEquals(StorageType.SQLITE, config.getSelectedStorageType());
        assertEquals(1_000D, config.getStartingBalance());
        assertEquals(100_000D, config.getMaxBalance());
        assertEquals(10, config.getLeaderboardTopEntries());
        assertEquals(50, config.getLeaderboardMaxEntries());
        assertEquals(10, config.getTransactionHistoryPageSize());
        assertFalse(config.isStorageDebug());
        assertFalse(config.isCommandsDebug());
        assertFalse(config.isBankingDebug());
        assertFalse(plugin.getConfig().getBoolean("storage.mysql.params.autoReconnect"));
        assertEquals(5_000, plugin.getConfig().getInt("storage.mysql.params.connectTimeout"));
        assertEquals(30_000, plugin.getConfig().getInt("storage.mysql.params.socketTimeout"));
        assertTrue(plugin.getConfig().getBoolean("storage.mysql.params.tcpKeepAlive"));
        assertTrue(config.isAsyncPersistenceEnabled());
        assertEquals(new AsyncPersistenceSettings(100L, 5_000L, 5_000L), config.getAsyncPersistenceSettings());
    }

    @Test
    void migratesTheLegacyStorageDebugPathToTheDocumentedPath() {
        plugin.getConfig().set("debug.storage", null);
        plugin.getConfig().set("storage.debug", true);

        ConfigManager config = new ConfigManager(plugin);

        assertTrue(config.isStorageDebug());
        assertFalse(plugin.getConfig().isSet("storage.debug"));
    }

    @Test
    void fallsBackToSqliteForAnUnsupportedStorageType() {
        plugin.getConfig().set("storage.type", "unsupported");

        ConfigManager config = new ConfigManager(plugin);

        assertEquals(StorageType.SQLITE, config.getSelectedStorageType());
        assertTrue(config.getConfiguredStorageType().isEmpty());
    }

    @Test
    void keepsMissingRequiredMySqlSettingsVisibleToStartupValidation() {
        plugin.getConfig().set("storage.mysql.host", null);

        new ConfigManager(plugin);
        StorageConfigurationValidator.ValidationResult result = new StorageConfigurationValidator()
                .validate(StorageType.MYSQL, plugin.getConfig());

        assertFalse(plugin.getConfig().contains("storage.mysql.host", true));
        assertFalse(result.valid());
    }

    @Test
    void rejectsInvalidAsynchronousPersistenceQueueSettings() {
        plugin.getConfig().set("persistence.async.initial_retry_delay_millis", 0L);

        ConfigManager config = new ConfigManager(plugin);

        assertThrows(IllegalArgumentException.class, config::getAsyncPersistenceSettings);
    }

    @Test
    void rejectsInvalidBoundedQueueSettings() {
        plugin.getConfig().set("persistence.async.queue_capacity", 0);

        ConfigManager config = new ConfigManager(plugin);

        assertThrows(IllegalArgumentException.class, config::getAsyncPersistenceSettings);
    }

    @Test
    void parsesConfiguredBalancesThroughExactMinorUnits() {
        plugin.getConfig().set("balance", "1000.005");
        plugin.getConfig().set("max_balance", "2500.005");

        ConfigManager config = new ConfigManager(plugin);

        assertEquals(1_000.01D, config.getStartingBalance());
        assertEquals(2_500.01D, config.getMaxBalance());
    }

    @Test
    void fallsBackToTheDocumentedStartingBalanceForAnInvalidMoneyValue() {
        plugin.getConfig().set("balance", "not-a-money-value");

        ConfigManager config = new ConfigManager(plugin);

        assertEquals(1_000D, config.getStartingBalance());
    }

    @Test
    void usesTheDocumentedHistoryPageSizeWhenTheConfiguredValueIsUnsafe() {
        plugin.getConfig().set("transactions.history-page-size", 101);

        ConfigManager config = new ConfigManager(plugin);

        assertEquals(10, config.getTransactionHistoryPageSize());
    }
    @Test
    void reloadsOnlyTheSafeRuntimeConfigurationFromDisk() throws Exception {
        ConfigManager config = plugin.getConfigManager();
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration disk = YamlConfiguration.loadConfiguration(file);
        disk.set("prefix.enabled", false);
        disk.set("prefix.full_prefix", "§7[Reloaded] ");
        disk.set("leaderboard.enabled", false);
        disk.set("leaderboard.top-entries", 3);
        disk.set("leaderboard.max-entries", 7);
        disk.set("currency.symbol", " Coins");
        disk.set("currency.display_after_amount", true);
        disk.set("balance", 25);
        disk.set("storage.type", "yaml");
        disk.set("debug.commands", true);
        disk.save(file);

        RuntimeConfigSnapshot reloaded = config.reloadRuntimeSettings();

        assertEquals("EN", config.readRuntimeReloadCandidate().languageCode());
        assertEquals(new RuntimeConfigSnapshot.PrefixSettings(false, "§7[Reloaded] "), reloaded.prefix());
        assertEquals(new RuntimeConfigSnapshot.LeaderboardSettings(false, 3, 7), reloaded.leaderboard());
        assertEquals(new RuntimeConfigSnapshot.CurrencySettings(" Coins", true), reloaded.currency());
        assertEquals(1_000D, config.getStartingBalance());
        assertEquals(StorageType.SQLITE, config.getSelectedStorageType());
        assertFalse(config.isCommandsDebug());
    }

    @Test
    void rejectsAnInvalidCandidateWithoutPartiallyReplacingRuntimeSettings() throws Exception {
        ConfigManager config = plugin.getConfigManager();
        RuntimeConfigSnapshot previous = config.getRuntimeSettings();
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration disk = YamlConfiguration.loadConfiguration(file);
        disk.set("prefix.full_prefix", "§c[MustNotApply] ");
        disk.set("currency.symbol", " Credits");
        disk.set("leaderboard.top-entries", 20);
        disk.set("leaderboard.max-entries", 5);
        disk.save(file);

        assertThrows(RuntimeConfigReloadException.class, config::reloadRuntimeSettings);
        assertEquals(previous, config.getRuntimeSettings());
    }

    @Test
    void rejectsMalformedYamlWithoutReplacingRuntimeSettings() throws Exception {
        ConfigManager config = plugin.getConfigManager();
        RuntimeConfigSnapshot previous = config.getRuntimeSettings();
        File file = new File(plugin.getDataFolder(), "config.yml");
        Files.writeString(file.toPath(), "prefix: [unterminated");

        assertThrows(RuntimeConfigReloadException.class, config::reloadRuntimeSettings);
        assertEquals(previous, config.getRuntimeSettings());
    }

    @Test
    void supportsRepeatedSafeRuntimeReloads() throws Exception {
        ConfigManager config = plugin.getConfigManager();
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration disk = YamlConfiguration.loadConfiguration(file);
        disk.set("currency.symbol", "€");
        disk.save(file);
        config.reloadRuntimeSettings();
        assertEquals("€", config.getCurrencySymbol());

        disk = YamlConfiguration.loadConfiguration(file);
        disk.set("currency.symbol", "Tokens ");
        disk.set("currency.display_after_amount", false);
        disk.save(file);
        config.reloadRuntimeSettings();

        assertEquals("Tokens ", config.getCurrencySymbol());
        assertFalse(config.isCurrencyDisplayAfterAmount());
    }

    @Test
    void rejectsAMissingConfigFileWithoutReplacingRuntimeSettings() throws Exception {
        ConfigManager config = plugin.getConfigManager();
        RuntimeConfigSnapshot previous = config.getRuntimeSettings();
        File file = new File(plugin.getDataFolder(), "config.yml");
        assertTrue(file.delete());

        assertThrows(RuntimeConfigReloadException.class, config::reloadRuntimeSettings);
        assertEquals(previous, config.getRuntimeSettings());
    }

    @Test
    void rejectsWrongRuntimeValueTypesWithoutReplacingRuntimeSettings() throws Exception {
        ConfigManager config = plugin.getConfigManager();
        RuntimeConfigSnapshot previous = config.getRuntimeSettings();
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration disk = YamlConfiguration.loadConfiguration(file);
        disk.set("prefix.enabled", "yes");
        disk.save(file);

        assertThrows(RuntimeConfigReloadException.class, config::reloadRuntimeSettings);
        assertEquals(previous, config.getRuntimeSettings());
    }

    @Test
    void readsTheReloadableLanguageSelectionFromDiskWithoutChangingPluginConfig() throws Exception {
        ConfigManager config = plugin.getConfigManager();
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration disk = YamlConfiguration.loadConfiguration(file);
        disk.set("language", "de");
        disk.save(file);

        ConfigManager.ReloadCandidate candidate = config.readRuntimeReloadCandidate();

        assertEquals("DE", candidate.languageCode());
        assertEquals("EN", plugin.getConfig().getString("language"));
    }

    @Test
    void exposesValidatedStartupOnlyVaultSettings() {
        ConfigManager config = plugin.getConfigManager();

        assertEquals(new de.sodaeconomy.integration.vault.VaultIntegrationSettings(true, 3_000L, 100L),
                config.getVaultIntegrationSettings());
        assertFalse(config.isIntegrationsDebug());
    }

    @Test
    void safeReloadDoesNotChangeVaultLifecycleSettings() throws Exception {
        ConfigManager config = plugin.getConfigManager();
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration disk = YamlConfiguration.loadConfiguration(file);
        disk.set("integrations.vault.enabled", false);
        disk.set("integrations.vault.operation-timeout-millis", 999L);
        disk.set("integrations.vault.warn-after-millis", 10L);
        disk.save(file);

        config.reloadRuntimeSettings();

        assertEquals(new de.sodaeconomy.integration.vault.VaultIntegrationSettings(true, 3_000L, 100L),
                config.getVaultIntegrationSettings());
    }

    @Test
    void rejectsInvalidVaultTimeoutSettingsWithoutBreakingOtherConfiguration() {
        plugin.getConfig().set("integrations.vault.operation-timeout-millis", 50L);
        ConfigManager config = new ConfigManager(plugin);

        assertThrows(IllegalArgumentException.class, config::getVaultIntegrationSettings);
        assertEquals(StorageType.SQLITE, config.getSelectedStorageType());
    }

}
