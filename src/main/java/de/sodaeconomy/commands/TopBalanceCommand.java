package de.sodaeconomy.commands;

import de.sodaeconomy.EconomyManager;
import de.sodaeconomy.SodaEconomy;
import de.sodaeconomy.identity.PlayerIdentityApi;
import de.sodaeconomy.storage.ConfigManager;
import de.sodaeconomy.storage.RuntimeConfigSnapshot;
import de.sodaeconomy.transaction.TransactionService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class TopBalanceCommand implements CommandExecutor {

    private record LeaderboardView(List<Map.Entry<UUID, Double>> balances, Map<UUID, String> displayNames) { }

    private final EconomyManager economyManager;
    private final SodaEconomy plugin;
    private final TransactionService transactionService;
    private final PlayerIdentityApi playerIdentityApi;
    private final ConfigManager configManager;
    private final RuntimeConfigSnapshot.LeaderboardSettings fixedSettings;

    @SuppressWarnings("removal")
    public TopBalanceCommand(EconomyManager economyManager, int defaultLimit, int maximumLimit) {
        this(economyManager, economyManager.getTransactionService(), defaultLimit, maximumLimit);
    }

    public TopBalanceCommand(EconomyManager economyManager, TransactionService transactionService,
                             int defaultLimit, int maximumLimit) {
        this(economyManager, transactionService, defaultLimit, maximumLimit,
                SodaEconomy.getInstance().getPlayerIdentityApi());
    }

    public TopBalanceCommand(EconomyManager economyManager, TransactionService transactionService,
                             int defaultLimit, int maximumLimit, PlayerIdentityApi playerIdentityApi) {
        this.economyManager = economyManager;
        this.transactionService = transactionService;
        this.playerIdentityApi = Objects.requireNonNull(playerIdentityApi, "playerIdentityApi");
        this.plugin = SodaEconomy.getInstance();
        this.configManager = null;
        int safeMaximum = Math.max(1, maximumLimit);
        this.fixedSettings = new RuntimeConfigSnapshot.LeaderboardSettings(true,
                Math.max(1, Math.min(defaultLimit, safeMaximum)), safeMaximum);
    }

    public TopBalanceCommand(EconomyManager economyManager, TransactionService transactionService,
                             ConfigManager configManager) {
        this(economyManager, transactionService, configManager,
                SodaEconomy.getInstance().getPlayerIdentityApi());
    }

    public TopBalanceCommand(EconomyManager economyManager, TransactionService transactionService,
                             ConfigManager configManager, PlayerIdentityApi playerIdentityApi) {
        this.economyManager = economyManager;
        this.transactionService = transactionService;
        this.playerIdentityApi = Objects.requireNonNull(playerIdentityApi, "playerIdentityApi");
        this.plugin = SodaEconomy.getInstance();
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        this.fixedSettings = null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        plugin.debugCommand(sender.getName() + " executed /" + label);
        RuntimeConfigSnapshot.LeaderboardSettings settings = currentSettings();
        if (!settings.enabled()) {
            sendMessage(sender, "leaderboard-disabled");
            return true;
        }
        int limit = settings.topEntries();
        if (args.length > 0) {
            try {
                limit = Math.max(1, Math.min(settings.maxEntries(), Integer.parseInt(args[0])));
            } catch (NumberFormatException ignored) {
                // Keep the configured default.
            }
        }
        final int requestedLimit = limit;

        transactionService.getAllBalancesAsynchronously()
                .thenApply(balances -> balances.entrySet().stream()
                        .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                        .limit(requestedLimit)
                        .toList())
                .thenCompose(sorted -> playerIdentityApi.resolveDisplayNames(
                        sorted.stream().map(Map.Entry::getKey).toList())
                        .thenApply(names -> new LeaderboardView(sorted, names)))
                .whenComplete((view, throwable) -> plugin.getServer().getScheduler().runTask(plugin,
                        () -> sendLeaderboard(sender, view, throwable)));
        return true;
    }

    private RuntimeConfigSnapshot.LeaderboardSettings currentSettings() {
        return configManager == null ? fixedSettings : configManager.getRuntimeSettings().leaderboard();
    }

    private void sendLeaderboard(CommandSender sender, LeaderboardView view, Throwable throwable) {
        if (!plugin.isEnabled()) return;
        if (throwable != null || view == null) {
            sendMessage(sender, "transaction-failed");
            return;
        }
        if (view.balances().isEmpty()) {
            sendMessage(sender, "topbalance-no-entries");
            return;
        }

        sendMessage(sender, "topbalance-header", "count", view.balances().size());
        int position = 1;
        for (Map.Entry<UUID, Double> entry : view.balances()) {
            String playerName = view.displayNames().getOrDefault(entry.getKey(),
                    playerIdentityApi.cachedDisplayName(entry.getKey()));
            sendMessage(sender, "topbalance-entry", "position", position++, "player", playerName,
                    "balance", economyManager.formatCurrency(entry.getValue()));
        }
    }

    private void sendMessage(CommandSender sender, String key, Object... replacements) {
        sender.sendMessage(plugin.getLanguageManager().getFormattedMessage(key, replacements));
    }
}
