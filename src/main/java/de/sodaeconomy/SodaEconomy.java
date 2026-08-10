package de.sodaeconomy;

import de.sodaeconomy.commands.BalanceCommand;
import de.sodaeconomy.commands.BankCommand;
import de.sodaeconomy.commands.DisabledFeatureCommand;
import de.sodaeconomy.commands.EcoCommand;
import de.sodaeconomy.commands.PayCommand;
import de.sodaeconomy.commands.TopBalanceCommand;
import de.sodaeconomy.language.LanguageFileCreator;
import de.sodaeconomy.language.LanguageManager;
import de.sodaeconomy.identity.PlayerIdentityApi;
import de.sodaeconomy.identity.PlayerIdentityService;
import de.sodaeconomy.integration.NoopIntegration;
import de.sodaeconomy.integration.OptionalIntegration;
import de.sodaeconomy.integration.vault.VaultIntegrationBootstrap;
import de.sodaeconomy.integration.placeholderapi.PlaceholderApiIntegrationBootstrap;
import de.sodaeconomy.integration.vault.VaultIntegrationSettings;
import de.sodaeconomy.storage.ConfigManager;
import de.sodaeconomy.storage.AsyncWalletTransactionStore;
import de.sodaeconomy.storage.Storage;
import de.sodaeconomy.storage.StorageManager;
import de.sodaeconomy.storage.RuntimeConfigReloadException;
import de.sodaeconomy.storage.RuntimeConfigSnapshot;
import de.sodaeconomy.storage.StorageStartupException;
import de.sodaeconomy.storage.StorageType;
import de.sodaeconomy.storage.WalletTransactionStore;
import de.sodaeconomy.transaction.EconomyTransactionApi;
import de.sodaeconomy.transaction.TransactionService;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.ServicePriority;

import java.util.Optional;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public class SodaEconomy extends JavaPlugin {

    private static SodaEconomy instance;

    private EconomyManager economyManager;
    private StorageManager storageManager;
    private ConfigManager configManager;
    private BankManager bankManager;
    private LanguageManager languageManager;
    private TransactionService transactionService;
    private PlayerIdentityService playerIdentityService;
    private OptionalIntegration vaultIntegration = NoopIntegration.INSTANCE;
    private OptionalIntegration placeholderApiIntegration = NoopIntegration.INSTANCE;
    private int interestTaskId = -1;
    private final AtomicBoolean warnedEconomyManagerAccess = new AtomicBoolean();
    private final AtomicBoolean warnedStorageManagerAccess = new AtomicBoolean();
    private final AtomicBoolean warnedConfigManagerAccess = new AtomicBoolean();
    private final AtomicBoolean warnedBankManagerAccess = new AtomicBoolean();
    private final AtomicBoolean warnedTransactionServiceAccess = new AtomicBoolean();

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        reloadConfig();
        configManager = new ConfigManager(this);

        boolean storageDebug = configManager.isStorageDebug();
        Optional<StorageType> configuredType = configManager.getConfiguredStorageType();
        if (configuredType.isEmpty()) {
            getLogger().severe("[Storage] storage.type is invalid. Use SQLITE, YAML, or MYSQL. "
                    + "No storage was initialized and no data was changed.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        StorageType selectedType = configuredType.get();
        getLogger().info("-------------------------------------------");
        getLogger().info("Starting SodaEconomy with requested storage " + selectedType + ".");
        if (storageDebug || configManager.isCommandsDebug() || configManager.isBankingDebug()
                || configManager.isIntegrationsDebug()) {
            getLogger().info("[Debug] Debug logging is enabled.");
        }

        // Storage initialization and a possible migration are complete before any mutable service starts.
        storageManager = new StorageManager(this);
        try {
            storageManager.init(selectedType);
        } catch (StorageStartupException exception) {
            getLogger().severe("[Storage] " + exception.getMessage());
            if (storageDebug) {
                getLogger().log(Level.SEVERE, "[Storage] Technical startup details:", exception);
            } else {
                getLogger().severe("[Storage] No data was migrated. Enable debug.storage for technical details.");
            }
            getServer().getPluginManager().disablePlugin(this);
            return;
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "[Storage] Unexpected storage initialization failure. No data was migrated.", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        Storage activeStorage = storageManager.getCurrent();
        if (activeStorage == null) {
            getLogger().severe("[Storage] No active storage was created. The plugin will be disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            LanguageFileCreator.createDefaultLanguageFile(this);
            languageManager = new LanguageManager(this);
            getLogger().info("[Language] Initialized language " + getConfig().getString("language", "EN") + ".");
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "[Language] Could not initialize the language system.", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (!(activeStorage instanceof WalletTransactionStore transactionStore)) {
            getLogger().severe("[Transactions] The selected storage does not support atomic wallet transactions.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        Storage runtimeStorage = activeStorage;
        WalletTransactionStore runtimeTransactionStore = transactionStore;
        try {
            if (configManager.isAsyncPersistenceEnabled() && storageManager.getCurrentType() != StorageType.MYSQL) {
                AsyncWalletTransactionStore asynchronousStorage = new AsyncWalletTransactionStore(this, activeStorage,
                        transactionStore, configManager.getAsyncPersistenceSettings());
                runtimeStorage = asynchronousStorage;
                runtimeTransactionStore = asynchronousStorage;
                getLogger().info("[Persistence] Ordered local asynchronous persistence is enabled.");
            } else if (storageManager.getCurrentType() == StorageType.MYSQL) {
                // MySQL is shared by multiple Paper instances. Its ACID transaction and row locks
                // must remain authoritative; a node-local write-behind cache could otherwise make
                // a stale balance appear spendable before MySQL rejects it.
                getLogger().info("[Persistence] MySQL uses database-authoritative asynchronous transactions.");
            } else {
                getLogger().info("[Persistence] Asynchronous persistence is disabled by configuration.");
            }
            transactionService = new TransactionService(this, runtimeTransactionStore,
                    configManager.getStartingBalance(), configManager.getMaxBalance());
            getServer().getServicesManager().register(EconomyTransactionApi.class, transactionService, this,
                    ServicePriority.Normal);
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "[Transactions] Could not initialize the transaction service.", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            playerIdentityService = new PlayerIdentityService(this, activeStorage);
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "[Identity] Could not initialize the player identity service.", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        economyManager = new EconomyManager(runtimeStorage, storageDebug, this, transactionService,
                playerIdentityService);
        initializeBanking(runtimeStorage);
        registerCommands();
        initializeOptionalIntegrations();

        getLogger().info("SodaEconomy started successfully with " + storageManager.getCurrentType() + ".");
        getLogger().info("-------------------------------------------");
    }

    private void initializeOptionalIntegrations() {
        try {
            VaultIntegrationSettings vaultSettings = configManager.getVaultIntegrationSettings();
            vaultIntegration = VaultIntegrationBootstrap.initialize(this, transactionService, playerIdentityService,
                    economyManager, languageManager, vaultSettings);
        } catch (Exception exception) {
            vaultIntegration = NoopIntegration.INSTANCE;
            getLogger().log(Level.WARNING,
                    "[Vault] Invalid Vault integration configuration. SodaEconomy continues without Vault support.",
                    exception);
        }

        placeholderApiIntegration = PlaceholderApiIntegrationBootstrap.initialize(
                this, transactionService, () -> configManager.getRuntimeSettings(), bankManager != null);
    }

    private void initializeBanking(Storage activeStorage) {
        if (!getConfig().getBoolean("banking.enabled", false)) {
            getLogger().info("[Banking] Banking is disabled by configuration.");
            return;
        }

        try {
            bankManager = new BankManager(activeStorage, economyManager, this, configManager.isBankingDebug());
            if (getCommand("bank") != null) getCommand("bank").setExecutor(
                    new BankCommand(bankManager, economyManager, playerIdentityService));

            if (getConfig().getBoolean("banking.interest_enabled", false)) {
                double rate = getConfig().getDouble("banking.interest_rate", 0.05);
                int intervalMinutes = getConfig().getInt("banking.interest_interval", 60);
                double maxInterest = getConfig().getDouble("banking.max_interest_per_account", 0.0);
                if (!Double.isFinite(rate) || rate <= 0 || intervalMinutes <= 0
                        || !Double.isFinite(maxInterest) || maxInterest < 0) {
                    throw new IllegalArgumentException("Invalid banking interest configuration");
                }
                long ticks = intervalMinutes * 60L * 20L;
                interestTaskId = new BankInterestTask(bankManager, rate, maxInterest, Duration.ofMinutes(intervalMinutes))
                        .runTaskTimerAsynchronously(this, ticks, ticks).getTaskId();
                getLogger().info("[Banking] Interest enabled (rate=" + rate + ", interval=" + intervalMinutes + " minutes).");
            } else {
                getLogger().info("[Banking] Interest is disabled by configuration.");
            }
            getLogger().info("[Banking] Banking initialized.");
        } catch (Exception exception) {
            bankManager = null;
            getLogger().log(Level.SEVERE, "[Banking] Banking could not be initialized and remains disabled.", exception);
        }
    }

    private void registerCommands() {
        if (getCommand("balance") != null) getCommand("balance").setExecutor(new BalanceCommand(economyManager, transactionService));
        if (getCommand("pay") != null) getCommand("pay").setExecutor(
                new PayCommand(economyManager, transactionService, playerIdentityService));
        if (getCommand("eco") != null) {
            EcoCommand ecoCommand = new EcoCommand(economyManager, transactionService, playerIdentityService);
            getCommand("eco").setExecutor(ecoCommand);
            getCommand("eco").setTabCompleter(ecoCommand);
        }

        if (getCommand("topbalance") != null) {
            getCommand("topbalance").setExecutor(new TopBalanceCommand(economyManager, transactionService,
                    configManager, playerIdentityService));
            RuntimeConfigSnapshot.LeaderboardSettings leaderboard = configManager.getRuntimeSettings().leaderboard();
            getLogger().info(leaderboard.enabled()
                    ? "[TopBalance] Enabled with " + leaderboard.topEntries() + " default entries."
                    : "[TopBalance] Disabled by configuration.");
        }

        if (getCommand("bank") != null && bankManager == null) {
            getCommand("bank").setExecutor(new DisabledFeatureCommand("banking-disabled"));
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("-------------------------------------------");
        getLogger().info("Stopping SodaEconomy.");

        if (interestTaskId != -1) {
            getServer().getScheduler().cancelTask(interestTaskId);
            if (configManager != null && configManager.isBankingDebug()) {
                getLogger().info("[Banking] Interest task stopped.");
            }
            interestTaskId = -1;
        }

        if (placeholderApiIntegration != null) {
            placeholderApiIntegration.close();
            placeholderApiIntegration = NoopIntegration.INSTANCE;
        }

        if (vaultIntegration != null) {
            vaultIntegration.close();
            vaultIntegration = NoopIntegration.INSTANCE;
        }

        if (playerIdentityService != null) {
            playerIdentityService.close();
            playerIdentityService = null;
        }

        if (transactionService != null) {
            transactionService.close();
            getServer().getServicesManager().unregister(EconomyTransactionApi.class, transactionService);
            transactionService = null;
        }

        if (storageManager != null) storageManager.close();
        getLogger().info("SodaEconomy stopped.");
        getLogger().info("-------------------------------------------");
    }

    /**
     * Legacy facade retained for binary compatibility. External plugins should obtain
     * {@link EconomyTransactionApi} from Bukkit's ServicesManager instead of mutating through a
     * manager implementation.
     */
    @Deprecated(since = "1.1", forRemoval = true)
    public EconomyManager getEconomyManager() {
        warnIfExternalLegacyAccess("SodaEconomy#getEconomyManager()", warnedEconomyManagerAccess);
        return economyManager;
    }

    /**
     * Not part of the supported API. Use {@link #getEconomyTransactionApi()} instead. This
     * internal-storage access bypasses the supported transaction API and will be removed in the
     * next major release.
     */
    @Deprecated(since = "1.1", forRemoval = true)
    public StorageManager getStorageManager() {
        warnIfExternalLegacyAccess("SodaEconomy#getStorageManager()", warnedStorageManagerAccess);
        return storageManager;
    }

    /** Not part of the supported API. Use {@link #getEconomyTransactionApi()} instead. */
    @Deprecated(since = "1.1", forRemoval = true)
    public ConfigManager getConfigManager() {
        warnIfExternalLegacyAccess("SodaEconomy#getConfigManager()", warnedConfigManagerAccess);
        return configManager;
    }

    /**
     * Not part of the supported API. Use {@link #getEconomyTransactionApi()} for wallet movement
     * and the built-in /bank commands until a dedicated bank API is introduced.
     */
    @Deprecated(since = "1.1", forRemoval = true)
    public BankManager getBankManager() {
        warnIfExternalLegacyAccess("SodaEconomy#getBankManager()", warnedBankManagerAccess);
        return bankManager;
    }
    public LanguageManager getLanguageManager() { return languageManager; }
    public EconomyTransactionApi getEconomyTransactionApi() { return transactionService; }
    /** Read-only player identity API for UUID/name resolution. */
    public PlayerIdentityApi getPlayerIdentityApi() { return playerIdentityService; }
    /**
     * Legacy concrete service access. Use {@link #getEconomyTransactionApi()} for supported
     * integrations; this concrete type exposes internal command operations.
     */
    @Deprecated(since = "1.1", forRemoval = true)
    public TransactionService getTransactionService() {
        warnIfExternalLegacyAccess("SodaEconomy#getTransactionService()", warnedTransactionServiceAccess);
        return transactionService;
    }


    /**
     * Atomically replaces only language, prefix, leaderboard and currency runtime settings. No
     * Bukkit configuration object, service, scheduler or storage component is reinitialized.
     */
    public RuntimeConfigSnapshot reloadSafeRuntimeConfiguration() throws RuntimeConfigReloadException {
        if (configManager == null) {
            throw new RuntimeConfigReloadException("The configuration manager is not initialized");
        }
        if (languageManager == null) {
            throw new RuntimeConfigReloadException("The language manager is not initialized");
        }
        debugCommand("Reading safe runtime settings and language selection from config.yml.");
        ConfigManager.ReloadCandidate candidate = configManager.readRuntimeReloadCandidate();
        LanguageManager.LanguageSnapshot languageSnapshot = languageManager.loadLanguageSnapshot(candidate.languageCode());

        configManager.applyRuntimeSettings(candidate.runtimeSettings());
        languageManager.applyLanguageSnapshot(languageSnapshot);
        debugCommand("Applied language, prefix, leaderboard and currency runtime settings atomically.");
        return candidate.runtimeSettings();
    }

    public void debugCommand(String message) {
        if (configManager != null && configManager.isCommandsDebug()) getLogger().info("[Commands] " + message);
    }

    public void debugBanking(String message) {
        if (configManager != null && configManager.isBankingDebug()) getLogger().info("[Banking] " + message);
    }

    public void debugIntegration(String message) {
        if (configManager != null && configManager.isIntegrationsDebug()) getLogger().info("[Integrations] " + message);
    }

    private void warnIfExternalLegacyAccess(String method, AtomicBoolean warned) {
        if (warned.get()) {
            return;
        }
        String caller = StackWalker.getInstance().walk(frames -> frames
                .map(StackWalker.StackFrame::getClassName)
                .filter(className -> !className.equals(SodaEconomy.class.getName()))
                .filter(className -> !className.startsWith("java."))
                .filter(className -> !className.startsWith("jdk."))
                .findFirst()
                .orElse("<unknown>"));
        if (!caller.startsWith("de.sodaeconomy.")) {
            if (warned.compareAndSet(false, true)) {
                getLogger().warning("[API] External access to " + method + " from " + caller
                        + " is deprecated and unsupported. Use EconomyTransactionApi from Bukkit ServicesManager instead.");
            }
        }
    }

    public static SodaEconomy getInstance() { return instance; }
}
