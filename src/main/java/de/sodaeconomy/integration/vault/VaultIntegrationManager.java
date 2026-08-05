package de.sodaeconomy.integration.vault;

import de.sodaeconomy.EconomyManager;
import de.sodaeconomy.SodaEconomy;
import de.sodaeconomy.identity.PlayerIdentityApi;
import de.sodaeconomy.integration.OptionalIntegration;
import de.sodaeconomy.language.LanguageManager;
import de.sodaeconomy.transaction.TransactionService;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Registers and owns the optional Vault Economy provider lifecycle. */
public final class VaultIntegrationManager implements OptionalIntegration {
    private final SodaEconomy plugin;
    private final VaultEconomyProvider provider;
    private final AtomicBoolean active = new AtomicBoolean();

    public VaultIntegrationManager(SodaEconomy plugin, TransactionService transactions,
                                   PlayerIdentityApi identities, EconomyManager economy,
                                   LanguageManager language, VaultIntegrationSettings settings) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        provider = new VaultEconomyProvider(plugin, new TransactionServiceVaultGateway(transactions), identities,
                economy, language, settings);
        register();
    }

    private void register() {
        ServicesManager services = plugin.getServer().getServicesManager();
        for (RegisteredServiceProvider<?> registration : services.getRegistrations(plugin)) {
            if (registration.getService() == Economy.class
                    && registration.getProvider() instanceof VaultEconomyProvider staleProvider) {
                staleProvider.deactivate();
                services.unregister(Economy.class, staleProvider);
                plugin.getLogger().warning("[Vault] Replaced a stale SodaEconomy Vault provider registration.");
            }
        }
        services.register(Economy.class, provider, plugin, ServicePriority.Normal);
        active.set(true);
        RegisteredServiceProvider<Economy> selected = services.getRegistration(Economy.class);
        if (selected != null && selected.getProvider() != provider) {
            plugin.getLogger().warning("[Vault] SodaEconomy registered successfully, but another provider currently "
                    + "has higher Vault service priority: " + selected.getProvider().getName() + ".");
        } else {
            plugin.getLogger().info("[Vault] Registered SodaEconomy as the Vault economy provider.");
        }
        plugin.debugIntegration("Vault economy provider registration completed.");
    }

    @Override public boolean isActive() { return active.get(); }

    @Override
    public void close() {
        if (!active.compareAndSet(true, false)) return;
        provider.deactivate();
        plugin.getServer().getServicesManager().unregister(Economy.class, provider);
        plugin.debugIntegration("Vault economy provider deregistered.");
        plugin.getLogger().info("[Vault] Deregistered SodaEconomy economy provider.");
    }
}
