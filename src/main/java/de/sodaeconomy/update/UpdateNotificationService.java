package de.sodaeconomy.update;

import de.sodaeconomy.SodaEconomy;
import de.sodaeconomy.language.LanguageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.net.URI;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Main-thread console/player notification layer for cached update results. */
public final class UpdateNotificationService implements Listener, AutoCloseable {
    public static final String UPDATE_PERMISSION = "sodaeconomy.admin.update";

    private final SodaEconomy plugin;
    private final UpdateCheckerService checker;
    private final LanguageManager languageManager;
    private final Set<UUID> notifiedSessions = new HashSet<>();
    private final Consumer<UpdateCheckResult> automaticListener = this::handleAutomaticResult;
    private String lastConsoleNotifiedVersion;
    private boolean closed;

    public UpdateNotificationService(SodaEconomy plugin, UpdateCheckerService checker,
                                     LanguageManager languageManager) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.checker = Objects.requireNonNull(checker, "checker");
        this.languageManager = Objects.requireNonNull(languageManager, "languageManager");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        checker.addAutomaticResultListener(automaticListener);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        notifyPlayerIfEligible(event.getPlayer(), checker.lastResult());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        notifiedSessions.remove(event.getPlayer().getUniqueId());
    }

    private void handleAutomaticResult(UpdateCheckResult result) {
        if (!result.updateAvailable()) return;
        UpdateCheckerSettings settings = checker.settings();
        if (!settings.enabled()) return;

        String latest = result.latest().orElseThrow().display();
        if (settings.notifyConsole() && !latest.equals(lastConsoleNotifiedVersion)) {
            lastConsoleNotifiedVersion = latest;
            plugin.getLogger().info("[Update] A new SodaEconomy version is available: " + latest
                    + " (installed: " + result.installedVersion() + ").");
            result.releasePageOptional().ifPresent(uri -> plugin.getLogger().info("[Update] Release: " + uri));
        }

        if (settings.notifyAdminsOnJoin()) {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                notifyPlayerIfEligible(player, result);
            }
        }
    }

    void notifyPlayerIfEligible(Player player, UpdateCheckResult result) {
        if (closed || player == null || result == null || !result.updateAvailable()) return;
        UpdateCheckerSettings settings = checker.settings();
        if (!settings.enabled() || !settings.notifyAdminsOnJoin()) return;
        if (!player.hasPermission(UPDATE_PERMISSION)) return;
        if (settings.notifyAdminsPerSession() && !notifiedSessions.add(player.getUniqueId())) return;

        player.sendMessage(languageManager.getFormattedMessage("update-available",
                "latest", result.latest().orElseThrow().display(),
                "installed", result.installedVersion()));
        result.releasePageOptional().ifPresent(uri -> sendReleaseLink(player, uri));
    }

    /** Sends a localized clickable release-page link to players and a plain URL to console senders. */
    public void sendReleaseLink(CommandSender sender, URI releasePage) {
        if (sender == null || releasePage == null) return;
        if (sender instanceof Player player) {
            Component component = LegacyComponentSerializer.legacySection().deserialize(
                    languageManager.getFormattedMessage("update-release-link"))
                    .clickEvent(ClickEvent.openUrl(releasePage.toString()));
            player.sendMessage(component);
        } else {
            sender.sendMessage(languageManager.getFormattedMessage("update-release-url", "url", releasePage));
        }
    }

    /** Clears per-session suppression after a reload that deliberately disables the checker. */
    public void onSettingsReloaded(UpdateCheckerSettings settings) {
        if (!settings.enabled() || !settings.notifyAdminsPerSession()) notifiedSessions.clear();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        checker.removeAutomaticResultListener(automaticListener);
        HandlerList.unregisterAll(this);
        notifiedSessions.clear();
    }
}
