package de.sodaeconomy.integration.placeholderapi;

import de.sodaeconomy.SodaEconomy;
import de.sodaeconomy.integration.NoopIntegration;
import de.sodaeconomy.integration.OptionalIntegration;
import de.sodaeconomy.transaction.EconomyTransactionApi;
import de.sodaeconomy.storage.RuntimeConfigSnapshot;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;

/** Class-loading-safe entry point for the optional PlaceholderAPI integration. */
public final class PlaceholderApiIntegrationBootstrap {
    private static final String PLUGIN_NAME = "PlaceholderAPI";

    private PlaceholderApiIntegrationBootstrap() { }

    public static OptionalIntegration initialize(SodaEconomy plugin, EconomyTransactionApi economyApi,
                                                 Supplier<RuntimeConfigSnapshot> runtimeSettings,
                                                 boolean bankingEnabled) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(economyApi, "economyApi");
        Objects.requireNonNull(runtimeSettings, "runtimeSettings");
        if (!plugin.getServer().getPluginManager().isPluginEnabled(PLUGIN_NAME)) {
            plugin.getLogger().info("[PlaceholderAPI] PlaceholderAPI not found; integration disabled.");
            return NoopIntegration.INSTANCE;
        }
        try {
            Class.forName("me.clip.placeholderapi.expansion.PlaceholderExpansion", false,
                    plugin.getClass().getClassLoader());
            OptionalIntegration integration = PlaceholderApiIntegration.start(plugin, economyApi, runtimeSettings, bankingEnabled);
            if (integration.isActive()) {
                plugin.getLogger().info("[PlaceholderAPI] Integration enabled for %sodaeconomy_...% placeholders.");
            } else {
                plugin.getLogger().warning("[PlaceholderAPI] Expansion registration was rejected; "
                        + "SodaEconomy continues without PlaceholderAPI support.");
            }
            return integration;
        } catch (LinkageError | ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "[PlaceholderAPI] Integration initialization failed; SodaEconomy continues normally.", exception);
            return NoopIntegration.INSTANCE;
        }
    }
}
