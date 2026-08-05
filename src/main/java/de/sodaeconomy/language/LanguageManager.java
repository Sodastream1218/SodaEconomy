package de.sodaeconomy.language;

import de.sodaeconomy.SodaEconomy;
import de.sodaeconomy.storage.ConfigManager;
import de.sodaeconomy.storage.RuntimeConfigReloadException;
import de.sodaeconomy.storage.RuntimeConfigSnapshot;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Loads localized messages and provides safe message lookup with bundled-language fallbacks. */
public final class LanguageManager {
    private static final String DEFAULT_LANGUAGE_CODE = "EN";
    private static final String DEFAULT_LANGUAGE_FILE = "messages_en.yml";
    private static final String MISSING_MESSAGE_KEY = "missing-message";

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final Set<String> reportedMissingKeys = ConcurrentHashMap.newKeySet();

    private volatile LanguageSnapshot languageSnapshot;
    private volatile RuntimeConfigSnapshot.PrefixSettings fallbackPrefixSettings;

    public LanguageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.configManager = plugin instanceof SodaEconomy sodaEconomy ? sodaEconomy.getConfigManager() : null;
        reload();
    }

    /** Reloads the configured language file, bundled defaults, and prefix settings. */
    public synchronized void reload() {
        String configuredLanguage = plugin.getConfig().getString("language", DEFAULT_LANGUAGE_CODE);
        try {
            applyLanguageSnapshot(loadLanguageSnapshot(configuredLanguage));
        } catch (RuntimeConfigReloadException exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "[Language] Could not load configured language; trying bundled English fallback.", exception);
            try {
                applyLanguageSnapshot(loadLanguageSnapshot(DEFAULT_LANGUAGE_CODE));
            } catch (RuntimeConfigReloadException fallbackException) {
                plugin.getLogger().log(Level.SEVERE, "[Language] English fallback could not be loaded.", fallbackException);
                languageSnapshot = new LanguageSnapshot(DEFAULT_LANGUAGE_CODE, new YamlConfiguration());
            }
        }
        loadPrefixSettings();
    }

    /**
     * Reads and validates a language snapshot without changing the active messages. Safe runtime
     * reload uses this to ensure that config and language can be published together atomically.
     */
    public LanguageSnapshot loadLanguageSnapshot(String configuredLanguage) throws RuntimeConfigReloadException {
        String languageCode = normalizeLanguageCode(configuredLanguage);
        String requestedFileName = fileNameFor(languageCode);

        File languageFolder = new File(plugin.getDataFolder(), "language");
        if (!languageFolder.exists() && !languageFolder.mkdirs()) {
            throw new RuntimeConfigReloadException("language directory could not be created");
        }

        File languageFile = new File(languageFolder, requestedFileName);
        if (!languageFile.isFile()) {
            createLanguageFile(languageFile, requestedFileName, languageCode);
        }

        YamlConfiguration messages = new YamlConfiguration();
        try {
            messages.load(languageFile);
        } catch (IOException | InvalidConfigurationException exception) {
            throw new RuntimeConfigReloadException("language file " + languageFile.getName() + " could not be read", exception);
        }

        YamlConfiguration defaults = loadBundledDefaults(requestedFileName);
        messages.setDefaults(defaults);
        validateMessageTypes(messages, defaults, languageFile.getName());
        return new LanguageSnapshot(languageCode, messages);
    }

    /** Atomically publishes a previously validated language snapshot. */
    public synchronized void applyLanguageSnapshot(LanguageSnapshot snapshot) {
        languageSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        reportedMissingKeys.clear();
        plugin.getLogger().info("[Language] Loaded language: " + snapshot.languageCode() + " ("
                + fileNameFor(snapshot.languageCode()) + ").");
    }

    private String normalizeLanguageCode(String configuredLanguage) throws RuntimeConfigReloadException {
        String languageCode = configuredLanguage == null || configuredLanguage.isBlank()
                ? DEFAULT_LANGUAGE_CODE
                : configuredLanguage.trim().toUpperCase(Locale.ROOT);
        if (!languageCode.matches("[A-Z0-9_-]{1,32}")) {
            throw new RuntimeConfigReloadException("language must be a simple language code");
        }
        return languageCode;
    }

    private String fileNameFor(String languageCode) {
        return "messages_" + languageCode.toLowerCase(Locale.ROOT) + ".yml";
    }

    private void createLanguageFile(File languageFile, String requestedFileName, String languageCode)
            throws RuntimeConfigReloadException {
        InputStream bundledFile = plugin.getResource(requestedFileName);
        if (bundledFile == null) {
            plugin.getLogger().warning("[Language] No bundled file for " + languageCode + " found; using EN fallback.");
            bundledFile = plugin.getResource(DEFAULT_LANGUAGE_FILE);
        }

        if (bundledFile == null) {
            throw new RuntimeConfigReloadException("no bundled default messages file is available");
        }

        try (InputStream resource = bundledFile) {
            Files.copy(resource, languageFile.toPath());
            plugin.getLogger().info("[Language] Created default language file: " + languageFile.getName());
        } catch (Exception exception) {
            throw new RuntimeConfigReloadException("language file " + languageFile.getName() + " could not be created", exception);
        }
    }

    /** Adds bundled defaults without overwriting a server administrator's custom language file. */
    private YamlConfiguration loadBundledDefaults(String requestedFileName) throws RuntimeConfigReloadException {
        InputStream bundledFile = plugin.getResource(requestedFileName);
        if (bundledFile == null) bundledFile = plugin.getResource(DEFAULT_LANGUAGE_FILE);
        if (bundledFile == null) {
            throw new RuntimeConfigReloadException("no bundled defaults are available for message fallback");
        }

        YamlConfiguration defaults = new YamlConfiguration();
        try (InputStream resource = bundledFile;
             InputStreamReader reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            defaults.load(reader);
            return defaults;
        } catch (IOException | InvalidConfigurationException exception) {
            throw new RuntimeConfigReloadException("bundled message defaults could not be loaded", exception);
        }
    }

    private void validateMessageTypes(YamlConfiguration messages, YamlConfiguration defaults, String fileName)
            throws RuntimeConfigReloadException {
        for (String key : defaults.getKeys(false)) {
            Object customValue = messages.get(key, null);
            if (customValue != null && !(customValue instanceof String)) {
                throw new RuntimeConfigReloadException("language file " + fileName + " contains a non-text value for " + key);
            }
            Object defaultValue = defaults.get(key);
            if (!(defaultValue instanceof String)) {
                throw new RuntimeConfigReloadException("bundled language defaults contain a non-text value for " + key);
            }
        }
        if (messages.getString(MISSING_MESSAGE_KEY) == null) {
            throw new RuntimeConfigReloadException("language file " + fileName + " has no missing-message fallback");
        }
    }

    private void loadPrefixSettings() {
        String prefixText = plugin.getConfig().getString("prefix.full_prefix", "§8[§bSodaEconomy§8] §r");
        fallbackPrefixSettings = new RuntimeConfigSnapshot.PrefixSettings(
                plugin.getConfig().getBoolean("prefix.enabled", true), prefixText == null ? "" : prefixText);
    }

    private RuntimeConfigSnapshot.PrefixSettings currentPrefixSettings() {
        return configManager == null ? fallbackPrefixSettings : configManager.getPrefixSettings();
    }

    /** Returns a localized message without a prefix. Missing keys fall back safely. */
    public String getMessage(String key) {
        LanguageSnapshot snapshot = languageSnapshot;
        String requestedKey = key == null || key.isBlank() ? "<empty>" : key;
        String message = snapshot.messages().getString(requestedKey);
        if (message != null) return message;

        if (reportedMissingKeys.add(requestedKey)) {
            plugin.getLogger().warning("[Language] Missing message key: " + requestedKey);
        }

        if (!MISSING_MESSAGE_KEY.equals(requestedKey)) {
            String missingTemplate = snapshot.messages().getString(MISSING_MESSAGE_KEY);
            if (missingTemplate != null) return missingTemplate.replace("{key}", requestedKey);
        }
        return requestedKey;
    }

    /** Replaces named placeholders in a localized message. */
    public String getMessage(String key, Object... replacements) {
        String message = getMessage(key);
        if (replacements == null || replacements.length == 0) return message;
        if (replacements.length % 2 != 0) {
            plugin.getLogger().warning("[Language] Ignored an incomplete placeholder replacement list for key: " + key);
            return message;
        }

        for (int index = 0; index < replacements.length; index += 2) {
            String placeholder = String.valueOf(replacements[index]);
            String value = String.valueOf(replacements[index + 1]);
            message = message.replace("{" + placeholder + "}", value);
        }
        return message;
    }

    public String getPrefix() {
        return currentPrefixSettings().fullPrefix();
    }

    public boolean isPrefixEnabled() {
        return currentPrefixSettings().enabled();
    }

    public String getLanguageCode() {
        return languageSnapshot.languageCode();
    }

    /** Returns a localized message with the current atomically reloaded prefix settings. */
    public String getFormattedMessage(String key, Object... replacements) {
        String message = getMessage(key, replacements);
        RuntimeConfigSnapshot.PrefixSettings prefix = currentPrefixSettings();
        return prefix.enabled() ? prefix.fullPrefix() + message : message;
    }

    public record LanguageSnapshot(String languageCode, YamlConfiguration messages) {
        public LanguageSnapshot {
            Objects.requireNonNull(languageCode, "languageCode");
            Objects.requireNonNull(messages, "messages");
        }
    }
}
