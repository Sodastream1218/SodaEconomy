package de.sodaeconomy.identity;

import de.sodaeconomy.SodaEconomy;
import de.sodaeconomy.storage.Storage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.ServicePriority;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Central identity service used by commands, leaderboards and future platform integrations. */
public final class PlayerIdentityService implements PlayerIdentityApi, AutoCloseable {
    private final SodaEconomy plugin;
    private final Storage storage;
    private final KnownPlayerRegistry registry = new KnownPlayerRegistry();
    private final PlayerPlatformBridge platformBridge;
    private final PlayerIdentityListener listener;
    private final ExecutorService persistenceExecutor;
    private final AtomicBoolean closed = new AtomicBoolean();

    public PlayerIdentityService(SodaEconomy plugin, Storage storage) throws Exception {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.platformBridge = ReflectiveFloodgateBridge.create(plugin);
        this.listener = new PlayerIdentityListener(this);
        this.persistenceExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SodaEconomy-player-identities");
            thread.setDaemon(true);
            return thread;
        });
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        plugin.getServer().getServicesManager().register(PlayerIdentityApi.class, this, plugin, ServicePriority.Normal);
        for (Player player : Bukkit.getOnlinePlayers()) observeLogin(player);
        plugin.getLogger().info("[Identity] Central player identity service initialized using "
                + platformBridge.name() + "; persistent identities are cached on demand.");
    }

    @Override
    public Optional<Player> findOnlinePlayer(String name) {
        requireServerThread("findOnlinePlayer(String)");
        if (name == null || name.isBlank()) return Optional.empty();
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null && exact.isOnline()) {
            observeCurrent(exact);
            return Optional.of(exact);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(name)) {
                observeCurrent(player);
                return Optional.of(player);
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<Player> findOnlinePlayer(UUID playerId) {
        requireServerThread("findOnlinePlayer(UUID)");
        if (playerId == null) return Optional.empty();
        Player player = Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline()) {
            observeCurrent(player);
            return Optional.of(player);
        }
        return Optional.empty();
    }


    @Override
    public List<String> suggestKnownNames(String prefix, int limit) {
        requireServerThread("suggestKnownNames");
        if (limit <= 0) return List.of();
        final String normalizedPrefix;
        try {
            normalizedPrefix = prefix == null || prefix.isBlank() ? "" : PlayerIdentity.normalizeName(prefix);
        } catch (IllegalArgumentException exception) {
            return List.of();
        }
        Map<String, String> namesByNormalizedValue = new java.util.LinkedHashMap<>();
        Bukkit.getOnlinePlayers().stream().map(Player::getName)
                .filter(name -> PlayerIdentity.normalizeName(name).startsWith(normalizedPrefix))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(name -> namesByNormalizedValue.putIfAbsent(PlayerIdentity.normalizeName(name), name));
        for (String name : registry.suggestNames(prefix, limit)) {
            namesByNormalizedValue.putIfAbsent(PlayerIdentity.normalizeName(name), name);
            if (namesByNormalizedValue.size() >= limit) break;
        }
        return namesByNormalizedValue.values().stream().limit(limit).toList();
    }

    void observeLogin(Player player) {
        observe(player, Instant.now(), true);
    }

    void observeDeparture(Player player) {
        observe(player, null, false);
    }

    private void observeCurrent(Player player) {
        observe(player, null, false);
    }

    private void observe(Player player, Instant loginAt, boolean persistLogin) {
        if (closed.get()) return;
        Optional<PlayerIdentity> existing = registry.find(player.getUniqueId());
        Instant effectiveLogin = loginAt != null ? loginAt
                : existing.map(PlayerIdentity::lastLoginAt).orElse(Instant.EPOCH);
        PlayerIdentity identity = PlayerIdentity.of(player.getUniqueId(), player.getName(), effectiveLogin,
                platformBridge.playerType(player.getUniqueId()));
        PlayerIdentity effective = registry.upsert(identity);
        boolean changed = existing.isEmpty() || !existing.get().equals(effective);
        if (persistLogin || changed) persistAsync(effective);
        if (changed && (plugin.getConfigManager().isCommandsDebug() || plugin.getConfigManager().isStorageDebug())) {
            plugin.getLogger().info("[Identity] Updated " + effective.playerId() + " as "
                    + effective.lastKnownName() + " (" + effective.playerType() + ").");
        }
    }

    private void persistAsync(PlayerIdentity identity) {
        if (closed.get()) return;
        persistenceExecutor.execute(() -> {
            try {
                PlayerIdentity persisted = storage.findPlayerIdentity(identity.playerId()).orElse(null);
                PlayerIdentity effective = PlayerIdentity.merge(persisted, identity);
                storage.upsertPlayerIdentity(effective);
                registry.upsert(effective);
            } catch (Exception exception) {
                plugin.getLogger().log(Level.WARNING,
                        "[Identity] Could not persist identity for " + identity.playerId() + ".", exception);
            }
        });
    }

    @Override public Optional<PlayerIdentity> findCached(UUID playerId) {
        return playerId == null ? Optional.empty() : registry.find(playerId);
    }

    @Override public Optional<PlayerIdentity> findCached(String playerName) {
        return playerName == null ? Optional.empty() : registry.findUnique(playerName);
    }

    @Override
    public CompletableFuture<Optional<PlayerIdentity>> resolve(UUID playerId) {
        if (playerId == null) return CompletableFuture.completedFuture(Optional.empty());
        if (Bukkit.isPrimaryThread()) {
            Optional<Player> online = findOnlinePlayer(playerId);
            if (online.isPresent()) {
                return CompletableFuture.completedFuture(registry.find(playerId));
            }
        }
        // Persisted identity data is authoritative across multiple Paper instances. Reading it
        // here avoids keeping a renamed player indefinitely under a stale node-local cache entry.
        return supplyStorage(() -> storage.findPlayerIdentity(playerId)).thenApply(found -> {
            found.ifPresent(registry::upsert);
            return found;
        });
    }

    @Override
    public CompletableFuture<Optional<PlayerIdentity>> resolve(String playerName) {
        if (playerName == null || playerName.isBlank()) return CompletableFuture.completedFuture(Optional.empty());
        if (Bukkit.isPrimaryThread()) {
            Optional<Player> online = findOnlinePlayer(playerName);
            if (online.isPresent()) {
                return CompletableFuture.completedFuture(registry.find(online.get().getUniqueId()));
            }
        }
        final String normalized;
        try {
            normalized = PlayerIdentity.normalizeName(playerName);
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        // Do not resolve an offline name solely from the process-local cache. Another backend may
        // have observed a rename or a collision since this instance cached the record.
        return supplyStorage(() -> storage.findPlayerIdentitiesByNormalizedName(normalized)).thenApply(matches -> {
            matches.forEach(registry::upsert);
            return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
        });
    }

    @Override
    public CompletableFuture<Map<UUID, String>> resolveDisplayNames(Collection<UUID> playerIds) {
        if (playerIds == null || playerIds.isEmpty()) return CompletableFuture.completedFuture(Map.of());
        Set<UUID> requested = new LinkedHashSet<>();
        for (UUID playerId : playerIds) if (playerId != null) requested.add(playerId);
        if (requested.isEmpty()) return CompletableFuture.completedFuture(Map.of());

        // One batch query per view keeps leaderboards network-current without causing an N+1 query
        // pattern. If the storage is temporarily unavailable, cached names remain a safe display
        // fallback; identity resolution itself still reports storage failures to its caller.
        Map<UUID, PlayerIdentity> cachedBeforeLoad = registry.findAll(requested);
        return supplyStorage(() -> storage.findPlayerIdentities(requested)).handle((persisted, throwable) -> {
            if (throwable == null && persisted != null) {
                persisted.values().forEach(registry::upsert);
            } else if (cachedBeforeLoad.isEmpty()) {
                throw new java.util.concurrent.CompletionException(unwrapCompletionFailure(throwable));
            }
            Map<UUID, String> result = new HashMap<>();
            for (UUID playerId : requested) result.put(playerId, cachedDisplayName(playerId));
            return Map.copyOf(result);
        });
    }

    @Override
    public String cachedDisplayName(UUID playerId) {
        if (playerId == null) return "Unknown";
        return registry.find(playerId).map(PlayerIdentity::lastKnownName)
                .orElse("Unknown-" + playerId.toString().substring(0, 8));
    }

    private static Throwable unwrapCompletionFailure(Throwable throwable) {
        if (throwable instanceof java.util.concurrent.CompletionException completion
                && completion.getCause() != null) {
            return completion.getCause();
        }
        return throwable == null ? new IllegalStateException("Identity lookup failed") : throwable;
    }

    private void requireServerThread(String operation) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(operation + " must be called from the Bukkit server thread");
        }
    }

    private <T> CompletableFuture<T> supplyStorage(ThrowingSupplier<T> supplier) {
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("Identity service is closed"));
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (Exception exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }, persistenceExecutor);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        HandlerList.unregisterAll(listener);
        plugin.getServer().getServicesManager().unregister(PlayerIdentityApi.class, this);
        persistenceExecutor.shutdown();
        try {
            if (!persistenceExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                List<Runnable> discarded = persistenceExecutor.shutdownNow();
                plugin.getLogger().warning("[Identity] Forced identity shutdown with " + discarded.size()
                        + " queued task(s) not executed.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            persistenceExecutor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> { T get() throws Exception; }
}
