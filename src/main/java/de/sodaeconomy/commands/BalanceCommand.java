package de.sodaeconomy.commands;

import de.sodaeconomy.EconomyManager;
import de.sodaeconomy.SodaEconomy;
import de.sodaeconomy.transaction.TransactionService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class BalanceCommand implements CommandExecutor {

    private final EconomyManager economyManager;
    private final SodaEconomy plugin;
    private final TransactionService transactionService;

    @SuppressWarnings("removal")
    public BalanceCommand(EconomyManager economyManager) {
        this(economyManager, economyManager.getTransactionService());
    }

    public BalanceCommand(EconomyManager economyManager, TransactionService transactionService) {
        this.economyManager = economyManager;
        this.transactionService = transactionService;
        this.plugin = SodaEconomy.getInstance();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        plugin.debugCommand(sender.getName() + " executed /" + label);
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getLanguageManager().getFormattedMessage("only-players"));
            return true;
        }

        transactionService.getBalanceAsynchronously(player.getUniqueId())
                .whenComplete((balance, throwable) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!plugin.isEnabled()) {
                        return;
                    }
                    if (throwable != null || balance == null) {
                        player.sendMessage(plugin.getLanguageManager().getFormattedMessage("transaction-failed"));
                        return;
                    }
                    player.sendMessage(plugin.getLanguageManager().getFormattedMessage(
                            "balance-display",
                            "balance", economyManager.formatCurrency(balance)
                    ));
                }));
        return true;
    }
}
