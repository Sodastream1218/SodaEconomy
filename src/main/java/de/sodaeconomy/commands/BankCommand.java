package de.sodaeconomy.commands;

import de.sodaeconomy.BankManager;
import de.sodaeconomy.EconomyManager;
import de.sodaeconomy.Money;
import de.sodaeconomy.SodaEconomy;
import de.sodaeconomy.identity.PlayerIdentity;
import de.sodaeconomy.identity.PlayerIdentityApi;
import de.sodaeconomy.transaction.TransactionFailureReason;
import de.sodaeconomy.transaction.TransactionOrigin;
import de.sodaeconomy.transaction.TransactionResult;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

public class BankCommand implements CommandExecutor {

    private final BankManager bankManager;
    private final EconomyManager economyManager;
    private final SodaEconomy plugin;
    private final PlayerIdentityApi playerIdentityApi;

    public BankCommand(BankManager bankManager, EconomyManager economyManager) {
        this(bankManager, economyManager, SodaEconomy.getInstance().getPlayerIdentityApi());
    }

    public BankCommand(BankManager bankManager, EconomyManager economyManager,
                       PlayerIdentityApi playerIdentityApi) {
        this.bankManager = bankManager;
        this.economyManager = economyManager;
        this.playerIdentityApi = Objects.requireNonNull(playerIdentityApi, "playerIdentityApi");
        this.plugin = SodaEconomy.getInstance();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        plugin.debugCommand(sender.getName() + " executed /" + label);
        if (args.length < 1) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "balance", "bal" -> handleBalance(sender, args);
            case "deposit" -> handleDeposit(sender, args);
            case "withdraw" -> handleWithdraw(sender, args);
            case "setbalance" -> handleSetBalance(sender, args);
            default -> sendUsage(sender);
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sendMessage(sender, "bank-commands-header");
        sendMessage(sender, "bank-command-balance");
        sendMessage(sender, "bank-command-deposit");
        sendMessage(sender, "bank-command-withdraw");
        if (sender.hasPermission("sodaeconomy.bank.admin")) sendMessage(sender, "bank-command-setbalance");
    }

    private void handleBalance(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            withKnownIdentity(sender, args[1], identity -> requestBalance(sender, identity.playerId(),
                    identity.lastKnownName()));
            return;
        }
        if (!(sender instanceof Player player)) {
            sendMessage(sender, "bank-balance-only-player");
            return;
        }
        requestBalance(sender, player.getUniqueId(), player.getName());
    }

    private void requestBalance(CommandSender sender, UUID playerId, String playerName) {
        bankManager.getBankBalanceAsynchronously(playerId).whenComplete((bankBalance, throwable) ->
                runOnMainThread(() -> {
                    if (throwable != null || bankBalance == null) {
                        sendMessage(sender, "economy-read-unavailable");
                        return;
                    }
                    sendMessage(sender, "bank-balance-display", "player", playerName,
                            "balance", economyManager.formatCurrency(bankBalance));
                }));
    }

    private void handleDeposit(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sendMessage(sender, "bank-deposit-only-player");
            return;
        }
        if (args.length < 2) {
            sendMessage(sender, "bank-deposit-usage");
            return;
        }
        double amount = parseAmount(args[1]);
        if (!Money.isPositive(amount)) {
            sendMessage(sender, "invalid-amount");
            return;
        }
        bankManager.depositTransactionAsynchronously(player.getUniqueId(), amount,
                TransactionOrigin.player(player.getUniqueId(), player.getName())).whenComplete((result, throwable) ->
                runOnMainThread(() -> sendTransferResult(sender, amount, result, throwable, true)));
    }

    private void handleWithdraw(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sendMessage(sender, "bank-withdraw-only-player");
            return;
        }
        if (args.length < 2) {
            sendMessage(sender, "bank-withdraw-usage");
            return;
        }
        double amount = parseAmount(args[1]);
        if (!Money.isPositive(amount)) {
            sendMessage(sender, "invalid-amount");
            return;
        }
        bankManager.withdrawTransactionAsynchronously(player.getUniqueId(), amount,
                TransactionOrigin.player(player.getUniqueId(), player.getName())).whenComplete((result, throwable) ->
                runOnMainThread(() -> sendTransferResult(sender, amount, result, throwable, false)));
    }

    private void handleSetBalance(CommandSender sender, String[] args) {
        if (!sender.hasPermission("sodaeconomy.bank.admin")) {
            sendMessage(sender, "no-permission");
            return;
        }
        if (args.length < 3) {
            sendMessage(sender, "bank-setbalance-usage");
            return;
        }
        double amount = parseAmount(args[2]);
        if (!Money.isValid(amount) || amount < 0) {
            sendMessage(sender, "invalid-amount");
            return;
        }
        withKnownIdentity(sender, args[1], identity -> bankManager
                .tryAdminSetBankBalanceAsynchronously(identity.playerId(), amount)
                .whenComplete((updated, throwable) -> runOnMainThread(() -> {
                    if (throwable != null || !Boolean.TRUE.equals(updated)) {
                        sendMessage(sender, "transaction-failed");
                        return;
                    }
                    sendMessage(sender, "bank-setbalance-success", "player", identity.lastKnownName(),
                            "amount", economyManager.formatCurrency(amount));
                })));
    }

    private void withKnownIdentity(CommandSender sender, String suppliedName, Consumer<PlayerIdentity> action) {
        playerIdentityApi.resolve(suppliedName).whenComplete((identity, throwable) -> {
            if (throwable != null) {
                runOnMainThread(() -> sendMessage(sender, "economy-read-unavailable"));
                return;
            }
            if (identity.isEmpty()) {
                runOnMainThread(() -> sendMessage(sender, "player-not-known", "player", suppliedName));
                return;
            }
            action.accept(identity.get());
        });
    }

    private double parseAmount(String value) {
        try {
            return Money.fromMinorUnits(Money.parseMinorUnits(value));
        } catch (IllegalArgumentException exception) {
            return -1;
        }
    }

    private void sendTransferResult(CommandSender sender, double amount, TransactionResult result, Throwable throwable,
                                    boolean deposit) {
        if (throwable != null || result == null || !result.isSuccessful()) {
            String key = result != null && result.failureReason() == TransactionFailureReason.INSUFFICIENT_FUNDS
                    ? (deposit ? "bank-deposit-not-enough" : "bank-withdraw-not-enough")
                    : "transaction-failed";
            sendMessage(sender, key);
            return;
        }
        sendMessage(sender, deposit ? "bank-deposit-success" : "bank-withdraw-success",
                "amount", economyManager.formatCurrency(amount));
    }

    private void sendMessage(CommandSender sender, String key, Object... replacements) {
        sender.sendMessage(plugin.getLanguageManager().getFormattedMessage(key, replacements));
    }

    private void runOnMainThread(Runnable action) {
        if (!plugin.isEnabled()) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (plugin.isEnabled()) action.run();
        });
    }
}
