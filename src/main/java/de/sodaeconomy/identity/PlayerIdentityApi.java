package de.sodaeconomy.identity;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Read-only public identity API. Obtain it from Bukkit's {@code ServicesManager}. Identity
 * mutations are owned by SodaEconomy and are updated from trusted server events.
 *
 * <p>The online-player lookup and suggestion methods must be called from the Bukkit server thread.
 * Persistent resolution and display-name batch methods are asynchronous and never fabricate an
 * account for an unknown name. Name resolution returns an empty result when a normalized name is
 * unknown or maps to more than one UUID.</p>
 *
 * @since 1.0
 */
public interface PlayerIdentityApi {
    /** Returns a process-local cached identity, without storage or Bukkit access. */
    Optional<PlayerIdentity> findCached(UUID playerId);

    /** Returns a uniquely cached case-insensitive name match, without storage or Bukkit access. */
    Optional<PlayerIdentity> findCached(String playerName);

    /** Finds an online player by UUID and refreshes the local identity observation. Server thread only. */
    Optional<Player> findOnlinePlayer(UUID playerId);

    /** Finds an online player by server-visible name. Matching is case-insensitive. Server thread only. */
    Optional<Player> findOnlinePlayer(String playerName);

    /**
     * Suggests online and process-cached server-visible names. This method intentionally performs
     * no database query because Bukkit tab completion is synchronous. Server thread only.
     */
    List<String> suggestKnownNames(String prefix, int limit);

    /** Resolves the current persistent identity by UUID on the identity executor. */
    CompletableFuture<Optional<PlayerIdentity>> resolve(UUID playerId);

    /** Resolves one unambiguous normalized persistent name on the identity executor. */
    CompletableFuture<Optional<PlayerIdentity>> resolve(String playerName);

    /**
     * Resolves display names using one backend batch lookup. The returned map contains every
     * non-null requested UUID and uses a stable {@code Unknown-xxxxxxxx} fallback when necessary.
     */
    CompletableFuture<Map<UUID, String>> resolveDisplayNames(Collection<UUID> playerIds);

    /** Returns the cached display name or a stable UUID-based fallback without performing I/O. */
    String cachedDisplayName(UUID playerId);
}
