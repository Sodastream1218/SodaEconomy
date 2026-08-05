package de.sodaeconomy.commands;

import de.sodaeconomy.EconomyManager;
import de.sodaeconomy.Money;
import de.sodaeconomy.SodaEconomy;
import de.sodaeconomy.identity.PlayerIdentityApi;
import de.sodaeconomy.transaction.TransactionFailureReason;
import de.sodaeconomy.transaction.TransactionOrigin;
import de.sodaeconomy.transaction.TransactionResult;
import de.sodaeconomy.transaction.TransactionService;
import de.sodaeconomy.transaction.TransactionType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

public class PayCommand implements CommandExecutor {

    private final EconomyManager economyManager;
    private final SodaEconomy plugin;
    private final TransactionService transactionService;
    private final PlayerIdentityApi playerIdentityApi;

    @SuppressWarnings("removal")
    public PayCommand(EconomyManager economyManager) {
        this(economyManager, economyManager.getTransactionService());
    }

    public PayCommand(EconomyManager economyManager, TransactionService transactionService) {
        this(economyManager, transactionService, SodaEconomy.getInstance().getPlayerIdentityApi());
    }

    public PayCommand(EconomyManager economyManager, TransactionService transactionService,
                      PlayerIdentityApi playerIdentityApi) {
        this.economyManager = economyManager;
        this.transactionService = transactionService;
        this.playerIdentityApi = java.util.Objects.requireNonNull(playerIdentityApi, "playerIdentityApi");
        this.plugin = SodaEconomy.getInstance();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        plugin.debugCommand(sender.getName() + " executed /" + label);

        if (!(sender instanceof Player player)) {
            sendMessage(sender, "only-players");
            return true;
        }

        if (args.length != 2) {
            sendMessage(player, "pay-usage");
            return true;
        }

        Player target = playerIdentityApi.findOnlinePlayer(args[0]).orElse(null);
        if (target == null) {
            sendMessage(player, "player-not-online", "player", args[0]);
            return true;
        }

        if (target.equals(player)) {
            sendMessage(player, "pay-self");
            return true;
        }

        double amount;
        try {
            amount = Money.fromMinorUnits(Money.parseMinorUnits(args[1]));
        } catch (IllegalArgumentException e) {
            sendMessage(player, "invalid-amount");
            return true;
        }

        if (!Money.isPositive(amount)) {
            sendMessage(player, "amount-must-be-positive");
            return true;
        }

        transactionService.transferAsynchronously(
                player.getUniqueId(), target.getUniqueId(), amount, TransactionType.PLAYER_TRANSFER,
                TransactionOrigin.player(player.getUniqueId(), player.getName()), "Player payment",
                Map.of("command", "pay")).whenComplete((result, throwable) -> plugin.getServer().getScheduler()
                .runTask(plugin, () -> sendResult(player, target, amount, result, throwable)));
        return true;
    }

    private void sendResult(Player sender, Player requestedTarget, double amount, TransactionResult result,
                            Throwable throwable) {
        if (!plugin.isEnabled()) {
            return;
        }
        if (throwable != null || result == null) {
            sendMessage(sender, "transaction-failed");
            return;
        }
        if (!result.isSuccessful()) {
            if (result.failureReason() == TransactionFailureReason.INSUFFICIENT_FUNDS) {
                sendMessage(sender, "not-enough-money");
            } else if (result.failureReason() == TransactionFailureReason.MAXIMUM_BALANCE_EXCEEDED) {
                sendMessage(sender, "target-max-balance", "player", requestedTarget.getName());
            } else {
                sendMessage(sender, "transaction-failed");
            }
            return;
        }

        String formattedAmount = economyManager.formatCurrency(amount);

        sendMessage(sender, "pay-success-sender", "player", requestedTarget.getName(), "amount", formattedAmount);

        Player target = playerIdentityApi.findOnlinePlayer(requestedTarget.getUniqueId()).orElse(null);
        if (target != null) {
            sendMessage(target, "pay-success-target", "player", sender.getName(), "amount", formattedAmount);
        }
    }

    private void sendMessage(CommandSender sender, String key, Object... replacements) {
        sender.sendMessage(plugin.getLanguageManager().getFormattedMessage(key, replacements));
    }
}
