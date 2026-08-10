package de.sodaeconomy.integration.placeholderapi;

import de.sodaeconomy.SodaEconomy;
import de.sodaeconomy.integration.NoopIntegration;
import de.sodaeconomy.integration.OptionalIntegration;
import de.sodaeconomy.transaction.EconomyTransactionApi;
import de.sodaeconomy.storage.RuntimeConfigSnapshot;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;

/** Active PlaceholderAPI lifecycle owned by SodaEconomy. Loaded only when PlaceholderAPI exists. */
final class PlaceholderApiIntegration implements OptionalIntegration {
    private final SodaEconomy plugin;
    private final PlaceholderEconomyCache cache;
    private final SodaEconomyPlaceholderExpansion expansion;
    private boolean active;

    private PlaceholderApiIntegration(SodaEconomy plugin, PlaceholderEconomyCache cache,
                                      SodaEconomyPlaceholderExpansion expansion) {
        this.plugin = plugin;
        this.cache = cache;
        this.expansion = expansion;
        this.active = true;
    }

    static OptionalIntegration start(SodaEconomy plugin, EconomyTransactionApi economyApi,
                                     Supplier<RuntimeConfigSnapshot> runtimeSettings, boolean bankingEnabled) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(economyApi, "economyApi");
        Objects.requireNonNull(runtimeSettings, "runtimeSettings");
        PlaceholderEconomyCache cache = new PlaceholderEconomyCache(plugin, economyApi, bankingEnabled);
        PlaceholderResolver resolver = new PlaceholderResolver(cache, runtimeSettings, bankingEnabled);
        SodaEconomyPlaceholderExpansion expansion = new SodaEconomyPlaceholderExpansion(plugin, resolver);
        if (!expansion.register()) {
            return NoopIntegration.INSTANCE;
        }
        try {
            cache.start();
            return new PlaceholderApiIntegration(plugin, cache, expansion);
        } catch (RuntimeException | LinkageError exception) {
            expansion.unregister();
            cache.close();
            throw exception;
        }
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void close() {
        if (!active) return;
        active = false;
        try {
            expansion.unregister();
        } catch (RuntimeException | LinkageError exception) {
            plugin.getLogger().log(Level.WARNING,
                    "[PlaceholderAPI] Expansion cleanup failed during shutdown.", exception);
        } finally {
            cache.close();
        }
    }
}
