package de.sodaeconomy.identity;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Optional Floodgate 2.x bridge loaded reflectively so Floodgate is never a hard dependency. */
final class ReflectiveFloodgateBridge implements PlayerPlatformBridge {
    private final JavaPlugin plugin;
    private final Object api;
    private final Method isFloodgatePlayer;
    private final AtomicBoolean warned = new AtomicBoolean();

    private ReflectiveFloodgateBridge(JavaPlugin plugin, Object api, Method isFloodgatePlayer) {
        this.plugin = plugin;
        this.api = api;
        this.isFloodgatePlayer = isFloodgatePlayer;
    }

    static PlayerPlatformBridge create(JavaPlugin plugin) {
        Plugin floodgate = plugin.getServer().getPluginManager().getPlugin("floodgate");
        if (floodgate == null || !floodgate.isEnabled()) return new NoopPlayerPlatformBridge();
        try {
            ClassLoader loader = floodgate.getClass().getClassLoader();
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi", true, loader);
            Object api = java.util.Objects.requireNonNull(apiClass.getMethod("getInstance").invoke(null),
                    "FloodgateApi#getInstance returned null");
            Method isFloodgatePlayer = apiClass.getMethod("isFloodgatePlayer", UUID.class);
            return new ReflectiveFloodgateBridge(plugin, api, isFloodgatePlayer);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "[Identity] Floodgate is installed but its API could not be initialized; continuing without it.",
                    exception);
            return new NoopPlayerPlatformBridge();
        }
    }

    @Override
    public PlayerType playerType(UUID playerId) {
        try {
            return Boolean.TRUE.equals(isFloodgatePlayer.invoke(api, playerId))
                    ? PlayerType.BEDROCK : PlayerType.JAVA;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            if (warned.compareAndSet(false, true)) {
                plugin.getLogger().log(Level.WARNING,
                        "[Identity] Floodgate player detection failed; affected players are marked UNKNOWN.", exception);
            }
            return PlayerType.UNKNOWN;
        }
    }

    @Override public String name() { return "Floodgate"; }
    @Override public boolean available() { return true; }
}
