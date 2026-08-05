package de.sodaeconomy.language;

import de.sodaeconomy.storage.RuntimeConfigReloadException;
import de.sodaeconomy.support.MockBukkitTestBase;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LanguageManagerTest extends MockBukkitTestBase {
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([^{}]+)}");

    @Test
    void replacesMessagePlaceholdersAndAppliesTheConfiguredPrefix() {
        String message = plugin.getLanguageManager().getFormattedMessage(
                "pay-success-sender", "player", "Bob", "amount", "$5.00");

        assertTrue(message.startsWith(plugin.getLanguageManager().getPrefix()));
        assertTrue(message.contains("Bob"));
        assertTrue(message.contains("$5.00"));
    }

    @Test
    void returnsTheLocalizedMissingMessageFallbackWithoutThrowing() {
        assertEquals("§cMissing message: unknown-key", plugin.getLanguageManager().getMessage("unknown-key"));
    }

    @Test
    void usesBundledDefaultsForKeysMissingFromACustomLanguageFile() throws Exception {
        File customLanguageFile = new File(plugin.getDataFolder(), "language/messages_en.yml");
        YamlConfiguration customizedMessages = YamlConfiguration.loadConfiguration(customLanguageFile);
        customizedMessages.set("invalid-amount", null);
        customizedMessages.save(customLanguageFile);

        LanguageManager manager = new LanguageManager(plugin);

        assertEquals(plugin.getLanguageManager().getMessage("invalid-amount"), manager.getMessage("invalid-amount"));
    }

    @Test
    void fallsBackToEnglishWhenTheConfiguredLanguageIsNotBundled() {
        plugin.getConfig().set("language", "ZZ");

        LanguageManager manager = new LanguageManager(plugin);

        assertTrue(new File(plugin.getDataFolder(), "language/messages_zz.yml").isFile());
        assertEquals(plugin.getLanguageManager().getMessage("invalid-amount"), manager.getMessage("invalid-amount"));
    }

    @Test
    void reloadsPrefixSettings() throws Exception {
        LanguageManager manager = plugin.getLanguageManager();
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration disk = YamlConfiguration.loadConfiguration(configFile);
        disk.set("prefix.enabled", false);
        disk.save(configFile);

        plugin.reloadSafeRuntimeConfiguration();

        assertFalse(manager.isPrefixEnabled());
        assertFalse(manager.getFormattedMessage("invalid-amount").startsWith(manager.getPrefix()));
    }


    @Test
    void safeReloadSwitchesLanguageFromConfigWithoutRestartingThePlugin() throws Exception {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration disk = YamlConfiguration.loadConfiguration(configFile);
        disk.set("language", "DE");
        disk.save(configFile);

        plugin.reloadSafeRuntimeConfiguration();

        assertEquals("DE", plugin.getLanguageManager().getLanguageCode());
        assertEquals("§cBitte gib einen gültigen Betrag ein!", plugin.getLanguageManager().getMessage("invalid-amount"));
        assertEquals("EN", plugin.getConfig().getString("language"));
    }

    @Test
    void safeReloadAppliesChangedLanguageFileContents() throws Exception {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration disk = YamlConfiguration.loadConfiguration(configFile);
        disk.set("language", "DE");
        disk.save(configFile);
        plugin.reloadSafeRuntimeConfiguration();

        File germanFile = new File(plugin.getDataFolder(), "language/messages_de.yml");
        YamlConfiguration german = YamlConfiguration.loadConfiguration(germanFile);
        german.set("invalid-amount", "§cReloaded German amount text");
        german.save(germanFile);

        plugin.reloadSafeRuntimeConfiguration();

        assertEquals("§cReloaded German amount text", plugin.getLanguageManager().getMessage("invalid-amount"));
    }

    @Test
    void safeReloadRejectsBrokenLanguageFileWithoutPartiallyReplacingRuntimeState() throws Exception {
        String previousLanguage = plugin.getLanguageManager().getLanguageCode();
        String previousPrefix = plugin.getLanguageManager().getPrefix();
        String previousCurrency = plugin.getEconomyManager().formatCurrency(25D);

        File configFile = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration disk = YamlConfiguration.loadConfiguration(configFile);
        disk.set("language", "DE");
        disk.set("prefix.full_prefix", "§4[NotApplied] ");
        disk.set("currency.symbol", " Broken");
        disk.save(configFile);

        File languageFolder = new File(plugin.getDataFolder(), "language");
        assertTrue(languageFolder.isDirectory() || languageFolder.mkdirs());
        Files.writeString(new File(languageFolder, "messages_de.yml").toPath(), "invalid-amount: [broken");

        assertThrows(RuntimeConfigReloadException.class, plugin::reloadSafeRuntimeConfiguration);
        assertEquals(previousLanguage, plugin.getLanguageManager().getLanguageCode());
        assertEquals(previousPrefix, plugin.getLanguageManager().getPrefix());
        assertEquals(previousCurrency, plugin.getEconomyManager().formatCurrency(25D));
    }

    @Test
    void safeReloadRejectsNonTextMessageValuesWithoutPartiallyReplacingRuntimeState() throws Exception {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration disk = YamlConfiguration.loadConfiguration(configFile);
        disk.set("language", "DE");
        disk.save(configFile);
        plugin.reloadSafeRuntimeConfiguration();
        String previousLanguage = plugin.getLanguageManager().getLanguageCode();
        String previousMessage = plugin.getLanguageManager().getMessage("invalid-amount");

        YamlConfiguration changedConfig = YamlConfiguration.loadConfiguration(configFile);
        changedConfig.set("language", "EN");
        changedConfig.save(configFile);
        File englishFile = new File(plugin.getDataFolder(), "language/messages_en.yml");
        YamlConfiguration english = YamlConfiguration.loadConfiguration(englishFile);
        english.set("invalid-amount", java.util.List.of("not", "text"));
        english.save(englishFile);

        assertThrows(RuntimeConfigReloadException.class, plugin::reloadSafeRuntimeConfiguration);
        assertEquals(previousLanguage, plugin.getLanguageManager().getLanguageCode());
        assertEquals(previousMessage, plugin.getLanguageManager().getMessage("invalid-amount"));
    }

    @Test
    void keepsEveryBundledLanguageFileOnTheSameKeySet() throws Exception {
        Set<String> englishKeys = loadBundledLanguage("messages_en.yml").getKeys(false);

        assertEquals(englishKeys, loadBundledLanguage("messages_de.yml").getKeys(false));
        assertEquals(englishKeys, loadBundledLanguage("messages_es.yml").getKeys(false));
    }

    @Test
    void preservesEveryPlaceholderAcrossBundledLanguages() throws Exception {
        YamlConfiguration english = loadBundledLanguage("messages_en.yml");
        YamlConfiguration german = loadBundledLanguage("messages_de.yml");
        YamlConfiguration spanish = loadBundledLanguage("messages_es.yml");

        for (String key : english.getKeys(false)) {
            Set<String> expectedPlaceholders = placeholders(english.getString(key));
            assertEquals(expectedPlaceholders, placeholders(german.getString(key)), "German placeholders for " + key);
            assertEquals(expectedPlaceholders, placeholders(spanish.getString(key)), "Spanish placeholders for " + key);
        }
    }

    private YamlConfiguration loadBundledLanguage(String fileName) throws Exception {
        try (InputStream resource = plugin.getResource(fileName)) {
            assertTrue(resource != null, "Missing bundled language file: " + fileName);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(resource, StandardCharsets.UTF_8));
        }
    }

    private Set<String> placeholders(String message) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(message == null ? "" : message);
        java.util.HashSet<String> result = new java.util.HashSet<>();
        while (matcher.find()) result.add(matcher.group(1));
        return result;
    }
}
