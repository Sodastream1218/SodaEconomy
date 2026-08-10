package de.sodaeconomy.integration.placeholderapi;

import de.sodaeconomy.SodaEconomy;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.Objects;

/** Internal PlaceholderAPI expansion for the %sodaeconomy_...% namespace. */
final class SodaEconomyPlaceholderExpansion extends PlaceholderExpansion {
    private final SodaEconomy plugin;
    private final PlaceholderResolver resolver;

    SodaEconomyPlaceholderExpansion(SodaEconomy plugin, PlaceholderResolver resolver) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public String getIdentifier() {
        return "sodaeconomy";
    }

    @Override
    public String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        return resolver.resolve(player == null ? null : player.getUniqueId(), params);
    }
}
