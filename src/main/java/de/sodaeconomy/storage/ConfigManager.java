package de.sodaeconomy.storage;

import de.sodaeconomy.Money;
import de.sodaeconomy.transaction.TransactionQuery;
import de.sodaeconomy.integration.vault.VaultIntegrationSettings;
import de.sodaeconomy.update.UpdateChannel;
import de.sodaeconomy.update.UpdateCheckerSettings;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class ConfigManager {
    private static final int DEFAULT_TRANSACTION_HISTORY_PAGE_SIZE = 10;

    private static final Set<String> REQUIRED_MYSQL_PATHS = Set.of(
            "storage.mysql.host",
            "storage.mysql.port",
            "storage.mysql.database",
            "storage.mysql.user",
            "storage.mysql.password"
    );

    private final JavaPlugin plugin;
    private final FileConfiguration config;
    private final AtomicReference<RuntimeConfigSnapshot> runtimeSettings = new AtomicReference<>();
    private final AtomicReference<UpdateCheckerSettings> updateCheckerSettings = new AtomicReference<>();

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
        initDefaults();
        runtimeSettings.set(loadInitialRuntimeSettings());
        updateCheckerSettings.set(loadInitialUpdateCheckerSettings());
    }

    private void initDefaults() {
        migrateLegacyStorageDebugPath();
        Set<String> missingRequiredMySqlPaths = missingRequiredMySqlPaths();

        // === Storage Defaults ===
        config.addDefault("storage.type", "sqlite");
        // MySQL connection fields intentionally receive no runtime defaults. A missing value must
        // remain visible to StorageConfigurationValidator instead of being masked before a migration.
        config.addDefault("storage.mysql.params.useSSL", false);
        config.addDefault("storage.mysql.params.autoReconnect", false);
        config.addDefault("storage.mysql.params.allowPublicKeyRetrieval", true);
        config.addDefault("storage.mysql.params.connectTimeout", 5000);
        config.addDefault("storage.mysql.params.socketTimeout", 30000);
        config.addDefault("storage.mysql.params.tcpKeepAlive", true);

        // === Asynchronous Persistence Defaults ===
        config.addDefault("persistence.async.enabled", true);
        config.addDefault("persistence.async.initial_retry_delay_millis", 100);
        config.addDefault("persistence.async.maximum_retry_delay_millis", 5_000);
        config.addDefault("persistence.async.shutdown_warning_interval_seconds", 5);
        config.addDefault("persistence.async.queue_capacity", AsyncPersistenceSettings.DEFAULT_QUEUE_CAPACITY);
        config.addDefault("persistence.async.warning_threshold_percent",
                AsyncPersistenceSettings.DEFAULT_WARNING_THRESHOLD_PERCENT);
        config.addDefault("persistence.async.maximum_retry_attempts",
                AsyncPersistenceSettings.DEFAULT_MAXIMUM_RETRY_ATTEMPTS);
        config.addDefault("persistence.async.recovery_probe_interval_seconds",
                AsyncPersistenceSettings.DEFAULT_RECOVERY_PROBE_INTERVAL_MILLIS / 1_000L);
        config.addDefault("persistence.async.shutdown_timeout_seconds",
                AsyncPersistenceSettings.DEFAULT_SHUTDOWN_TIMEOUT_MILLIS / 1_000L);

        // === Transaction display defaults ===
        config.addDefault("transactions.history-page-size", DEFAULT_TRANSACTION_HISTORY_PAGE_SIZE);

        // === Debug Defaults ===
        config.addDefault("debug.storage", false);
        config.addDefault("debug.commands", false);
        config.addDefault("debug.banking", false);
        config.addDefault("debug.integrations", false);

        // === Optional integrations (startup-only) ===
        config.addDefault("integrations.vault.enabled", true);
        config.addDefault("integrations.vault.operation-timeout-millis",
                VaultIntegrationSettings.DEFAULT_OPERATION_TIMEOUT_MILLIS);
        config.addDefault("integrations.vault.warn-after-millis",
                VaultIntegrationSettings.DEFAULT_WARN_AFTER_MILLIS);

        // === Optional update checker (reload-safe) ===
        UpdateCheckerSettings updateDefaults = UpdateCheckerSettings.defaults();
        config.addDefault("update-checker.enabled", updateDefaults.enabled());
        config.addDefault("update-checker.source", updateDefaults.source());
        config.addDefault("update-checker.channel", updateDefaults.channel().configValue());
        config.addDefault("update-checker.check-on-startup", updateDefaults.checkOnStartup());
        config.addDefault("update-checker.check-interval-hours", updateDefaults.checkIntervalHours());
        config.addDefault("update-checker.notify-console", updateDefaults.notifyConsole());
        config.addDefault("update-checker.notify-admins-on-join", updateDefaults.notifyAdminsOnJoin());
        config.addDefault("update-checker.notify-admins-per-session", updateDefaults.notifyAdminsPerSession());
        config.addDefault("update-checker.connect-timeout-seconds", updateDefaults.connectTimeoutSeconds());
        config.addDefault("update-checker.read-timeout-seconds", updateDefaults.readTimeoutSeconds());

        // === Leaderboard Defaults ===
        config.addDefault("leaderboard.enabled", true);
        config.addDefault("leaderboard.top-entries", 10);
        config.addDefault("leaderboard.max-entries", 50);

        // === Economy Defaults ===
        config.addDefault("balance", 1000.0);
        config.addDefault("max_balance", 100000.0);

        // === Currency Section ===
        config.addDefault("currency.symbol", "$");
        config.addDefault("currency.display_after_amount", false);

        config.options().copyDefaults(true);
        // JavaPlugin also exposes the bundled config as defaults. Keep absent required MySQL keys
        // absent after copying the other safe defaults so validation can distinguish an old or
        // incomplete configuration from the bundled example values.
        missingRequiredMySqlPaths.forEach(path -> config.set(path, null));
        plugin.saveConfig();
    }

    private Set<String> missingRequiredMySqlPaths() {
        Set<String> missing = new LinkedHashSet<>();
        for (String path : REQUIRED_MYSQL_PATHS) {
            if (!config.contains(path, true)) missing.add(path);
        }
        return missing;
    }

    /**
     * Moves the short-lived legacy setting to the documented setting before defaults are applied.
     * Looking at the explicitly stored values prevents a default value from masking a user's old
     * configuration during an upgrade.
     */
    private void migrateLegacyStorageDebugPath() {
        boolean hasLegacyValue = config.contains("storage.debug", true);
        boolean hasDocumentedValue = config.contains("debug.storage", true);

        if (hasLegacyValue && !hasDocumentedValue) {
            config.set("debug.storage", config.getBoolean("storage.debug"));
        }

        config.set("storage.debug", null);
    }

    // === Storage ===
    /**
     * Returns the explicitly configured storage type. Unlike {@link #getSelectedStorageType()},
     * this method never silently converts a typo into SQLite and is therefore suitable during
     * plugin startup.
     */
    public Optional<StorageType> getConfiguredStorageType() {
        return StorageType.parse(config.getString("storage.type"));
    }

    /**
     * Compatibility accessor for callers that historically expected SQLite as a safe default.
     * Startup code must use {@link #getConfiguredStorageType()} so configuration mistakes are
     * reported instead of causing an unintended storage switch.
     */
    public StorageType getSelectedStorageType() {
        return StorageType.fromString(config.getString("storage.type", "sqlite"));
    }

    public boolean isStorageDebug() { return config.getBoolean("debug.storage", false); }
    public boolean isCommandsDebug() { return config.getBoolean("debug.commands", false); }
    public boolean isBankingDebug() { return config.getBoolean("debug.banking", false); }
    public boolean isIntegrationsDebug() { return config.getBoolean("debug.integrations", false); }

    /** Startup-only Vault settings. /eco reload intentionally does not replace this snapshot. */
    public VaultIntegrationSettings getVaultIntegrationSettings() {
        return new VaultIntegrationSettings(
                config.getBoolean("integrations.vault.enabled", true),
                config.getLong("integrations.vault.operation-timeout-millis",
                        VaultIntegrationSettings.DEFAULT_OPERATION_TIMEOUT_MILLIS),
                config.getLong("integrations.vault.warn-after-millis",
                        VaultIntegrationSettings.DEFAULT_WARN_AFTER_MILLIS));
    }

    // === Safe runtime configuration ===
    public RuntimeConfigSnapshot getRuntimeSettings() {
        return runtimeSettings.get();
    }

    /**
     * Reads and validates the explicitly reloadable values from config.yml without mutating any
     * live state. Callers can validate other runtime systems first and then publish all snapshots
     * together. The plugin's primary FileConfiguration is intentionally not replaced, so every
     * startup-only value keeps its original runtime meaning until the next server restart.
     */
    public synchronized ReloadCandidate readRuntimeReloadCandidate() throws RuntimeConfigReloadException {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.isFile()) {
            throw new RuntimeConfigReloadException("config.yml does not exist");
        }

        YamlConfiguration reloaded = new YamlConfiguration();
        try {
            reloaded.load(configFile);
        } catch (IOException | InvalidConfigurationException exception) {
            throw new RuntimeConfigReloadException("config.yml could not be read", exception);
        }

        RuntimeConfigSnapshot candidate;
        UpdateCheckerSettings updateCandidate;
        String languageCode;
        try {
            candidate = parseRuntimeSettings(reloaded);
            updateCandidate = parseUpdateCheckerSettings(reloaded);
            languageCode = normalizeLanguageCode(requireString(reloaded, "language", 32));
        } catch (IllegalArgumentException exception) {
            throw new RuntimeConfigReloadException(exception.getMessage(), exception);
        }
        return new ReloadCandidate(candidate, updateCandidate, languageCode);
    }

    /**
     * Compatibility entry point for existing internal tests and utilities that only need the safe
     * runtime subset. The full /eco reload path uses {@link #readRuntimeReloadCandidate()} and
     * publishes the config and language snapshots atomically after both have been validated.
     */
    public synchronized RuntimeConfigSnapshot reloadRuntimeSettings() throws RuntimeConfigReloadException {
        ReloadCandidate candidate = readRuntimeReloadCandidate();
        applyRuntimeSettings(candidate.runtimeSettings());
        return candidate.runtimeSettings();
    }

    public void applyRuntimeSettings(RuntimeConfigSnapshot candidate) {
        runtimeSettings.set(candidate);
    }

    public UpdateCheckerSettings getUpdateCheckerSettings() {
        return updateCheckerSettings.get();
    }

    public void applyUpdateCheckerSettings(UpdateCheckerSettings candidate) {
        updateCheckerSettings.set(java.util.Objects.requireNonNull(candidate, "candidate"));
    }

    public record ReloadCandidate(RuntimeConfigSnapshot runtimeSettings, UpdateCheckerSettings updateCheckerSettings,
                                  String languageCode) {
        public ReloadCandidate {
            java.util.Objects.requireNonNull(runtimeSettings, "runtimeSettings");
            java.util.Objects.requireNonNull(updateCheckerSettings, "updateCheckerSettings");
            java.util.Objects.requireNonNull(languageCode, "languageCode");
        }
    }

    public boolean isLeaderboardEnabled() {
        return getRuntimeSettings().leaderboard().enabled();
    }

    public int getLeaderboardTopEntries() {
        return getRuntimeSettings().leaderboard().topEntries();
    }

    public int getLeaderboardMaxEntries() {
        return getRuntimeSettings().leaderboard().maxEntries();
    }

    public RuntimeConfigSnapshot.PrefixSettings getPrefixSettings() {
        return getRuntimeSettings().prefix();
    }

    // === Economy ===
    public double getStartingBalance() {
        return getConfiguredMoney("balance", 1_000_00L, true);
    }

    public double getMaxBalance() {
        return getConfiguredMoney("max_balance", 10_000_000L, false);
    }

    public String getCurrencySymbol() {
        return getRuntimeSettings().currency().symbol();
    }

    public boolean isCurrencyDisplayAfterAmount() {
        return getRuntimeSettings().currency().displayAfterAmount();
    }

    /** Returns a safe, bounded page size for history commands and database-backed ledger queries. */
    public int getTransactionHistoryPageSize() {
        int pageSize = config.getInt("transactions.history-page-size", DEFAULT_TRANSACTION_HISTORY_PAGE_SIZE);
        if (pageSize < 1 || pageSize > TransactionQuery.MAX_LIMIT) {
            plugin.getLogger().warning("[Config] transactions.history-page-size must be between 1 and "
                    + TransactionQuery.MAX_LIMIT + "; using " + DEFAULT_TRANSACTION_HISTORY_PAGE_SIZE + ".");
            return DEFAULT_TRANSACTION_HISTORY_PAGE_SIZE;
        }
        return pageSize;
    }

    // === Asynchronous persistence ===
    public boolean isAsyncPersistenceEnabled() {
        return config.getBoolean("persistence.async.enabled", true);
    }

    /** Returns validated queue settings read from the persisted configuration. */
    public AsyncPersistenceSettings getAsyncPersistenceSettings() {
        long initialRetryDelay = config.getLong("persistence.async.initial_retry_delay_millis");
        long maximumRetryDelay = config.getLong("persistence.async.maximum_retry_delay_millis");
        long shutdownWarningInterval = Math.multiplyExact(
                config.getLong("persistence.async.shutdown_warning_interval_seconds"), 1_000L);
        int queueCapacity = config.getInt("persistence.async.queue_capacity");
        int warningThresholdPercent = config.getInt("persistence.async.warning_threshold_percent");
        int maximumRetryAttempts = config.getInt("persistence.async.maximum_retry_attempts");
        long recoveryProbeInterval = Math.multiplyExact(
                config.getLong("persistence.async.recovery_probe_interval_seconds"), 1_000L);
        long shutdownTimeout = Math.multiplyExact(
                config.getLong("persistence.async.shutdown_timeout_seconds"), 1_000L);
        return new AsyncPersistenceSettings(initialRetryDelay, maximumRetryDelay, shutdownWarningInterval,
                queueCapacity, warningThresholdPercent, maximumRetryAttempts, recoveryProbeInterval,
                shutdownTimeout);
    }

    public void save() {
        plugin.saveConfig();
    }


    private RuntimeConfigSnapshot loadInitialRuntimeSettings() {
        String prefix = config.getString("prefix.full_prefix", "§8[§bSodaEconomy§8] §r");
        String symbol = config.getString("currency.symbol", "$");
        int maxEntries = Math.max(1, config.getInt("leaderboard.max-entries", 50));
        int topEntries = Math.max(1, Math.min(config.getInt("leaderboard.top-entries", 10), maxEntries));
        return new RuntimeConfigSnapshot(
                new RuntimeConfigSnapshot.PrefixSettings(config.getBoolean("prefix.enabled", true),
                        prefix == null ? "" : prefix),
                new RuntimeConfigSnapshot.LeaderboardSettings(config.getBoolean("leaderboard.enabled", true),
                        topEntries, maxEntries),
                new RuntimeConfigSnapshot.CurrencySettings(symbol == null ? "$" : symbol,
                        config.getBoolean("currency.display_after_amount", false)));
    }

    private UpdateCheckerSettings loadInitialUpdateCheckerSettings() {
        try {
            return parseUpdateCheckerSettings(config);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("[Update] Invalid update-checker configuration; update checks are disabled: "
                    + exception.getMessage());
            return UpdateCheckerSettings.disabledDefaults();
        }
    }

    private UpdateCheckerSettings parseUpdateCheckerSettings(ConfigurationSection source) {
        String sourceName = requireString(source, "update-checker.source", 32).trim().toLowerCase(java.util.Locale.ROOT);
        UpdateChannel channel = UpdateChannel.parse(requireString(source, "update-checker.channel", 16))
                .orElseThrow(() -> new IllegalArgumentException(
                        "update-checker.channel must be one of: stable, rc, beta, alpha"));
        return new UpdateCheckerSettings(
                requireBoolean(source, "update-checker.enabled"),
                sourceName,
                channel,
                requireBoolean(source, "update-checker.check-on-startup"),
                requireIntegerInRange(source, "update-checker.check-interval-hours",
                        UpdateCheckerSettings.MIN_CHECK_INTERVAL_HOURS, UpdateCheckerSettings.MAX_CHECK_INTERVAL_HOURS),
                requireBoolean(source, "update-checker.notify-console"),
                requireBoolean(source, "update-checker.notify-admins-on-join"),
                requireBoolean(source, "update-checker.notify-admins-per-session"),
                requireIntegerInRange(source, "update-checker.connect-timeout-seconds",
                        UpdateCheckerSettings.MIN_TIMEOUT_SECONDS, UpdateCheckerSettings.MAX_CONNECT_TIMEOUT_SECONDS),
                requireIntegerInRange(source, "update-checker.read-timeout-seconds",
                        UpdateCheckerSettings.MIN_TIMEOUT_SECONDS, UpdateCheckerSettings.MAX_READ_TIMEOUT_SECONDS));
    }

    private String normalizeLanguageCode(String configuredLanguage) {
        String normalized = configuredLanguage == null || configuredLanguage.isBlank()
                ? "EN"
                : configuredLanguage.trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("language must be a simple language code");
        }
        return normalized;
    }

    private RuntimeConfigSnapshot parseRuntimeSettings(ConfigurationSection source) {
        boolean prefixEnabled = requireBoolean(source, "prefix.enabled");
        String fullPrefix = requireString(source, "prefix.full_prefix", 512);
        boolean leaderboardEnabled = requireBoolean(source, "leaderboard.enabled");
        int topEntries = requirePositiveInteger(source, "leaderboard.top-entries");
        int maxEntries = requirePositiveInteger(source, "leaderboard.max-entries");
        String currencySymbol = requireString(source, "currency.symbol", 128);
        boolean displayAfterAmount = requireBoolean(source, "currency.display_after_amount");

        return new RuntimeConfigSnapshot(
                new RuntimeConfigSnapshot.PrefixSettings(prefixEnabled, fullPrefix),
                new RuntimeConfigSnapshot.LeaderboardSettings(leaderboardEnabled, topEntries, maxEntries),
                new RuntimeConfigSnapshot.CurrencySettings(currencySymbol, displayAfterAmount));
    }

    private boolean requireBoolean(ConfigurationSection source, String path) {
        Object value = source.get(path);
        if (!(value instanceof Boolean booleanValue)) {
            throw new IllegalArgumentException(path + " must be true or false");
        }
        return booleanValue;
    }

    private String requireString(ConfigurationSection source, String path, int maximumLength) {
        Object value = source.get(path);
        if (!(value instanceof String stringValue)) {
            throw new IllegalArgumentException(path + " must be a text value");
        }
        if (stringValue.length() > maximumLength) {
            throw new IllegalArgumentException(path + " must not exceed " + maximumLength + " characters");
        }
        return stringValue;
    }

    private int requirePositiveInteger(ConfigurationSection source, String path) {
        Object value = source.get(path);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(path + " must be a positive whole number");
        }
        double decimal = number.doubleValue();
        long integral = number.longValue();
        if (!Double.isFinite(decimal) || decimal != integral || integral < 1L || integral > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(path + " must be a positive whole number");
        }
        return (int) integral;
    }

    private int requireIntegerInRange(ConfigurationSection source, String path, int minimum, int maximum) {
        Object value = source.get(path);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(path + " must be a whole number between " + minimum + " and " + maximum);
        }
        double decimal = number.doubleValue();
        long integral = number.longValue();
        if (!Double.isFinite(decimal) || decimal != integral || integral < minimum || integral > maximum) {
            throw new IllegalArgumentException(path + " must be a whole number between " + minimum + " and " + maximum);
        }
        return (int) integral;
    }

    /**
     * Reads money through the exact decimal parser instead of Bukkit's double conversion. This
     * keeps the configuration boundary aligned with the cent-based economy representation.
     */
    private double getConfiguredMoney(String path, long defaultMinor, boolean nonNegative) {
        Object rawValue = config.get(path);
        if (rawValue == null) {
            return Money.fromMinorUnits(defaultMinor);
        }
        try {
            long minor = Money.parseMinorUnits(String.valueOf(rawValue));
            if (nonNegative && minor < 0L) {
                throw new IllegalArgumentException("The value must not be negative");
            }
            return Money.fromMinorUnits(minor);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("[Config] " + path + " must be a "
                    + (nonNegative ? "non-negative " : "")
                    + "decimal money value; using the documented default.");
            return Money.fromMinorUnits(defaultMinor);
        }
    }
}
