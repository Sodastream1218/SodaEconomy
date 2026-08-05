package de.sodaeconomy.integration.vault;

import de.sodaeconomy.EconomyManager;
import de.sodaeconomy.SodaEconomy;
import de.sodaeconomy.identity.PlayerIdentityApi;
import de.sodaeconomy.integration.NoopIntegration;
import de.sodaeconomy.integration.OptionalIntegration;
import de.sodaeconomy.language.LanguageManager;
import de.sodaeconomy.transaction.TransactionService;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.util.logging.Level;

/** Loads Vault-specific bytecode only when Vault is actually installed and enabled. */
public final class VaultIntegrationBootstrap {
    private static final String MANAGER_CLASS = "de.sodaeconomy.integration.vault.VaultIntegrationManager";

    private VaultIntegrationBootstrap() { }

    public static OptionalIntegration initialize(SodaEconomy plugin, TransactionService transactions,
                                                  PlayerIdentityApi identities, EconomyManager economy,
                                                  LanguageManager language, VaultIntegrationSettings settings) {
        if (!settings.enabled()) {
            plugin.debugIntegration("Vault integration is disabled by startup configuration.");
            return NoopIntegration.INSTANCE;
        }
        Plugin vault = plugin.getServer().getPluginManager().getPlugin("Vault");
        if (vault == null || !vault.isEnabled()) {
            plugin.debugIntegration("Vault is not installed; SodaEconomy continues without a Vault provider.");
            return NoopIntegration.INSTANCE;
        }
        try {
            Class.forName("net.milkbowl.vault.economy.Economy", false, plugin.getClass().getClassLoader());
            Class<?> managerType = Class.forName(MANAGER_CLASS, true, plugin.getClass().getClassLoader());
            Constructor<?> constructor = managerType.getConstructor(SodaEconomy.class, TransactionService.class,
                    PlayerIdentityApi.class, EconomyManager.class, LanguageManager.class,
                    VaultIntegrationSettings.class);
            OptionalIntegration integration = (OptionalIntegration) constructor.newInstance(plugin, transactions,
                    identities, economy, language, settings);
            return integration.isActive() ? integration : NoopIntegration.INSTANCE;
        } catch (Throwable failure) {
            plugin.getLogger().log(Level.WARNING,
                    "[Vault] Vault was detected, but the optional economy provider could not be registered. "
                            + "SodaEconomy will continue normally without Vault support.", failure);
            return NoopIntegration.INSTANCE;
        }
    }
}
